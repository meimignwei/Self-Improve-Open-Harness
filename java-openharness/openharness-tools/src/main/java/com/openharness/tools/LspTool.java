package com.openharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Lightweight code intelligence tool for Python workspaces.
 * Wraps pylsp to provide symbol lookup, goto-definition, references, and hover.
 */
public class LspTool extends BaseTool<LspTool.Input> {

    private static final Logger LOG = Logger.getLogger(LspTool.class.getName());
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    /** LSP SymbolKind numbers to human-readable kind names. */
    private static final Map<Integer, String> SYMBOL_KINDS = Map.ofEntries(
            Map.entry(1, "File"), Map.entry(2, "Module"), Map.entry(3, "Namespace"),
            Map.entry(4, "Package"), Map.entry(5, "Class"), Map.entry(6, "Method"),
            Map.entry(7, "Property"), Map.entry(8, "Field"), Map.entry(9, "Constructor"),
            Map.entry(10, "Enum"), Map.entry(11, "Interface"), Map.entry(12, "Function"),
            Map.entry(13, "Variable"), Map.entry(14, "Constant"), Map.entry(15, "String"),
            Map.entry(16, "Number"), Map.entry(17, "Boolean"), Map.entry(18, "Array"),
            Map.entry(19, "Object"), Map.entry(20, "Key"), Map.entry(21, "Null"),
            Map.entry(22, "EnumMember"), Map.entry(23, "Struct"), Map.entry(24, "Event"),
            Map.entry(25, "Operator"), Map.entry(26, "TypeParameter")
    );

    public LspTool() {
        super("lsp",
                "Inspect Python code symbols, definitions, references, and hover information " +
                "across the current workspace.",
                Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        String op = arguments.operation();
        Path root = context.cwd();

        if ("workspace_symbol".equals(op)) {
            String query = arguments.query();
            if (query == null || query.isBlank()) {
                return ToolResult.error("workspace_symbol requires query");
            }
            List<SymbolEntry> symbols = pylspWorkspaceSymbols(root, query);
            return ToolResult.success(formatSymbolLocations(symbols, root));
        }

        if (arguments.filePath() == null || arguments.filePath().isBlank()) {
            return ToolResult.error(op + " requires file_path");
        }
        Path filePath = root.resolve(arguments.filePath()).normalize();
        if (!Files.exists(filePath)) {
            return ToolResult.error("File not found: " + filePath);
        }
        if (!filePath.toString().endsWith(".py")) {
            return ToolResult.error("The lsp tool currently supports Python files only.");
        }

        return switch (op) {
            case "document_symbol" -> {
                List<SymbolEntry> symbols = pylspDocumentSymbols(root, filePath);
                yield ToolResult.success(formatSymbolLocations(symbols, root));
            }
            case "go_to_definition" -> {
                List<SymbolEntry> results = pylspDefinitions(root, filePath,
                        arguments.symbol(), arguments.line(), arguments.character());
                yield ToolResult.success(formatSymbolLocations(results, root));
            }
            case "find_references" -> {
                List<ReferenceEntry> results = pylspReferences(root, filePath,
                        arguments.symbol(), arguments.line(), arguments.character());
                yield ToolResult.success(formatReferences(results, root));
            }
            case "hover" -> {
                SymbolEntry entry = pylspHover(root, filePath,
                        arguments.symbol(), arguments.line(), arguments.character());
                if (entry == null) {
                    yield ToolResult.success("(no hover result)");
                }
                yield ToolResult.success(formatHover(entry, root));
            }
            default -> ToolResult.error("Unknown LSP operation: " + op);
        };
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    // -------------------------------------------------------------------------
    // pylsp subprocess operations
    // -------------------------------------------------------------------------

    private List<SymbolEntry> pylspDocumentSymbols(Path root, Path filePath) {
        JsonNode params = MAPPER.createObjectNode()
                .set("textDocument", MAPPER.createObjectNode()
                        .put("uri", filePath.toUri().toString()));
        JsonNode result = callPylsp(root, "textDocument/documentSymbol", params);
        return parseSymbolResults(result, root);
    }

    private List<SymbolEntry> pylspDefinitions(Path root, Path filePath,
                                                String symbol, Integer line, Integer character) {
        JsonNode params = buildPositionParams(filePath, symbol, line, character);
        JsonNode result = callPylsp(root, "textDocument/definition", params);
        return parseSymbolResults(result, root);
    }

    private List<ReferenceEntry> pylspReferences(Path root, Path filePath,
                                                   String symbol, Integer line, Integer character) {
        JsonNode params = buildPositionParams(filePath, symbol, line, character);
        JsonNode result = callPylsp(root, "textDocument/references", params);
        return parseReferenceResults(result, root);
    }

    private SymbolEntry pylspHover(Path root, Path filePath,
                                     String symbol, Integer line, Integer character) {
        JsonNode params = buildPositionParams(filePath, symbol, line, character);
        JsonNode result = callPylsp(root, "textDocument/hover", params);
        if (result == null || result.isNull() || result.isEmpty()) return null;
        return parseHoverResult(result, filePath, line, character, symbol);
    }

    private List<SymbolEntry> pylspWorkspaceSymbols(Path root, String query) {
        JsonNode params = MAPPER.createObjectNode().put("query", query);
        JsonNode result = callPylsp(root, "workspace/symbol", params);
        return parseSymbolResults(result, root);
    }

    // -------------------------------------------------------------------------
    // JSON-RPC call to pylsp
    // -------------------------------------------------------------------------

    private JsonNode callPylsp(Path root, String method, JsonNode params) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python", "-m", "pylsp");
            pb.directory(root.toFile());
            Process process = pb.start();

            String initReq = MAPPER.writeValueAsString(jsonRpc("initialize", MAPPER.createObjectNode()
                    .put("processId", process.pid())
                    .set("capabilities", MAPPER.createObjectNode()))) + "\n";
            process.getOutputStream().write(initReq.getBytes());
            process.getOutputStream().flush();

            // Send initialized notification
            String initialized = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("method", "initialized")
                    .set("params", MAPPER.createObjectNode())) + "\n";
            process.getOutputStream().write(initialized.getBytes());
            process.getOutputStream().flush();

            // Read the initialize response to clear the buffer
            readOneJsonMessage(process);

            // Send the actual request
            String req = MAPPER.writeValueAsString(jsonRpc(method, params)) + "\n";
            process.getOutputStream().write(req.getBytes());
            process.getOutputStream().flush();

            // Read the response
            JsonNode response = readOneJsonMessage(process);

            process.destroyForcibly();

            if (response == null) return null;
            JsonNode result = response.get("result");
            JsonNode error = response.get("error");
            if (error != null && !error.isNull()) {
                LOG.warning("LSP error for " + method + ": " + error);
                return null;
            }
            return result;
        } catch (Exception e) {
            LOG.warning("LSP call failed for " + method + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads one complete JSON object (potentially multi-line) from the process stdout.
     * Handles the Content-Length header used by LSP.
     */
    private JsonNode readOneJsonMessage(Process process) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder content = new StringBuilder();

            // Try Content-Length header first (LSP standard)
            String line;
            int contentLength = -1;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
                if (line.isEmpty()) break; // end of headers
            }
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                reader.read(buf, 0, contentLength);
                return MAPPER.readTree(new String(buf));
            }

            // Fallback: read line by line looking for a JSON object
            String line2;
            while ((line2 = reader.readLine()) != null) {
                content.append(line2);
                if (line2.trim().endsWith("}")) {
                    try {
                        return MAPPER.readTree(content.toString());
                    } catch (Exception e) {
                        // keep reading
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private ObjectNode jsonRpc(String method, JsonNode params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", UUID.randomUUID().toString());
        node.put("method", method);
        node.set("params", params);
        return node;
    }

    // -------------------------------------------------------------------------
    // Parameter building
    // -------------------------------------------------------------------------

    private JsonNode buildPositionParams(Path filePath, String symbol,
                                          Integer line, Integer character) {
        ObjectNode params = MAPPER.createObjectNode();
        params.set("textDocument", MAPPER.createObjectNode()
                .put("uri", filePath.toUri().toString()));

        if (line != null && line > 0) {
            ObjectNode pos = MAPPER.createObjectNode();
            pos.put("line", line - 1); // Convert 1-based to 0-based
            pos.put("character", character != null && character > 0 ? character - 1 : 0);
            params.set("position", pos);
        }

        if (symbol != null && !symbol.isBlank()) {
            // Store symbol name for possible filtering
            params.put("_symbol", symbol);
        }
        return params;
    }

    // -------------------------------------------------------------------------
    // Response parsing (symbol results)
    // -------------------------------------------------------------------------

    /**
     * Parses LSP symbol results (documentSymbol, definition, workspace/symbol).
     * Handles arrays of SymbolInformation, DocumentSymbol, or single Location.
     */
    private List<SymbolEntry> parseSymbolResults(JsonNode result, Path root) {
        List<SymbolEntry> entries = new ArrayList<>();
        if (result == null || result.isNull()) return entries;

        if (result.isArray()) {
            for (JsonNode item : result) {
                SymbolEntry entry = parseOneSymbol(item, root);
                if (entry != null) entries.add(entry);
            }
        } else if (result.isObject()) {
            // Single result
            SymbolEntry entry = parseOneSymbol(result, root);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private SymbolEntry parseOneSymbol(JsonNode node, Path root) {
        if (node == null || node.isNull()) return null;

        String name = text(node, "name");
        int kind = intVal(node, "kind");
        String kindName = SYMBOL_KINDS.getOrDefault(kind, "kind:" + kind);

        // DocumentSymbol format: selectionRange and optional detail
        // SymbolInformation format: location { uri, range }
        JsonNode location = node.get("location");
        JsonNode range = null;
        JsonNode selectionRange = node.get("selectionRange");
        String detail = text(node, "detail");

        int line = 0;
        int character = 0;
        Path filePath = null;

        if (location != null) {
            JsonNode uriNode = location.get("uri");
            if (uriNode != null) {
                filePath = uriToPath(uriNode.asText());
            }
            range = location.get("range");
        }
        if (filePath == null && node.has("_filePath")) {
            filePath = Path.of(text(node, "_filePath"));
        }

        if (range == null) {
            range = selectionRange;
        }
        if (range != null) {
            JsonNode start = range.get("start");
            if (start != null) {
                line = intVal(start, "line") + 1; // 0-based to 1-based
                character = intVal(start, "character") + 1;
            } else {
                line = intVal(range, "line") + 1;
                character = 1;
            }
        }

        // Try containerName for package/module prefix
        String container = text(node, "containerName");
        if (container != null && !container.isBlank() && !container.equals(name)) {
            name = container + "." + name;
        }

        return new SymbolEntry(kindName, name, filePath, line, character,
                detail != null && !detail.isBlank() ? detail : null, null);
    }

    // -------------------------------------------------------------------------
    // Response parsing (references)
    // -------------------------------------------------------------------------

    private List<ReferenceEntry> parseReferenceResults(JsonNode result, Path root) {
        List<ReferenceEntry> entries = new ArrayList<>();
        if (result == null || result.isNull() || !result.isArray()) return entries;

        for (JsonNode item : result) {
            JsonNode uriNode = item.get("uri");
            JsonNode range = item.get("range");
            if (uriNode == null || range == null) continue;

            Path filePath = uriToPath(uriNode.asText());
            JsonNode start = range.get("start");
            int line = start != null ? intVal(start, "line") + 1 : 1;
            int character = start != null ? intVal(start, "character") + 1 : 1;

            // Try to read the line text from the file
            String lineText = readLineText(filePath, line);

            entries.add(new ReferenceEntry(filePath, line, character, lineText));
        }
        return entries;
    }

    // -------------------------------------------------------------------------
    // Response parsing (hover)
    // -------------------------------------------------------------------------

    private SymbolEntry parseHoverResult(JsonNode result, Path filePath,
                                          Integer line, Integer character, String symbol) {
        String contents = "";
        JsonNode contentsNode = result.get("contents");
        if (contentsNode != null && contentsNode.isObject()) {
            // MarkupContent format: { kind, value }
            contents = text(contentsNode, "value");
        } else if (contentsNode != null && contentsNode.isTextual()) {
            contents = contentsNode.asText();
        } else if (contentsNode != null && contentsNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : contentsNode) {
                if (item.isTextual()) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(item.asText());
                } else if (item.isObject()) {
                    String v = text(item, "value");
                    if (v != null) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(v);
                    }
                }
            }
            contents = sb.toString();
        }

        int l = line != null ? line : 0;
        int c = character != null ? character : 0;
        String name = symbol != null ? symbol : "";

        // Parse contents for signature/docstring split (Python-style hover)
        String signature = null;
        String docstring = null;
        if (contents != null && contents.contains("\n\n")) {
            int split = contents.indexOf("\n\n");
            signature = contents.substring(0, split).strip();
            docstring = contents.substring(split + 2).strip();
        } else if (contents != null && !contents.isBlank()) {
            signature = contents.strip();
        }

        return new SymbolEntry("", name, filePath, l, c, signature, docstring);
    }

    // -------------------------------------------------------------------------
    // Output formatting (matching Python output)
    // -------------------------------------------------------------------------

    private String formatSymbolLocations(List<SymbolEntry> entries, Path root) {
        if (entries.isEmpty()) return "(no results)";
        StringBuilder sb = new StringBuilder();
        for (SymbolEntry entry : entries) {
            sb.append(entry.kind()).append(" ").append(entry.name())
                    .append(" - ").append(displayPath(entry.path(), root));
            if (entry.line() > 0) {
                sb.append(":").append(entry.line());
                if (entry.character() > 0) sb.append(":").append(entry.character());
            }
            sb.append("\n");
            if (entry.signature() != null && !entry.signature().isBlank()) {
                sb.append("  signature: ").append(entry.signature()).append("\n");
            }
            if (entry.docstring() != null && !entry.docstring().isBlank()) {
                sb.append("  docstring: ").append(entry.docstring().strip()).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private String formatReferences(List<ReferenceEntry> entries, Path root) {
        if (entries.isEmpty()) return "(no results)";
        StringBuilder sb = new StringBuilder();
        for (ReferenceEntry entry : entries) {
            sb.append(displayPath(entry.path(), root))
                    .append(":").append(entry.line())
                    .append(":").append(entry.text() != null ? entry.text().strip() : "")
                    .append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private String formatHover(SymbolEntry entry, Path root) {
        StringBuilder sb = new StringBuilder();
        if (!entry.kind().isBlank()) {
            sb.append(entry.kind()).append(" ").append(entry.name()).append("\n");
        }
        sb.append("path: ").append(displayPath(entry.path(), root));
        if (entry.line() > 0) sb.append(":").append(entry.line());
        if (entry.character() > 0) sb.append(":").append(entry.character());
        if (entry.signature() != null && !entry.signature().isBlank()) {
            sb.append("\nsignature: ").append(entry.signature());
        }
        if (entry.docstring() != null && !entry.docstring().isBlank()) {
            sb.append("\ndocstring: ").append(entry.docstring().strip());
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String displayPath(Path path, Path root) {
        if (path == null) return "";
        try {
            return root.relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    private Path uriToPath(String uri) {
        if (uri == null) return null;
        String path = uri;
        if (path.startsWith("file://")) {
            path = path.substring(7);
        }
        return Path.of(path);
    }

    private String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    private int intVal(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && n.isNumber() ? n.asInt() : 0;
    }

    private String readLineText(Path filePath, int oneBasedLine) {
        try {
            List<String> lines = Files.readAllLines(filePath);
            int idx = oneBasedLine - 1;
            if (idx >= 0 && idx < lines.size()) {
                return lines.get(idx);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    private record SymbolEntry(String kind, String name, Path path, int line, int character,
                                String signature, String docstring) {}

    private record ReferenceEntry(Path path, int line, int character, String text) {}

    // -------------------------------------------------------------------------
    // Input record
    // -------------------------------------------------------------------------

    public record Input(String operation, String filePath, String symbol,
                        Integer line, Integer character, String query) {
        public Input {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("operation is required");
            }
            // Validation matching Python's model_validator
            if ("workspace_symbol".equals(operation)) {
                if (query == null || query.isBlank()) {
                    throw new IllegalArgumentException("workspace_symbol requires query");
                }
            } else {
                if (filePath == null || filePath.isBlank()) {
                    throw new IllegalArgumentException(operation + " requires file_path");
                }
                if (!"document_symbol".equals(operation)
                        && symbol == null && line == null) {
                    throw new IllegalArgumentException(operation + " requires symbol or line");
                }
            }
        }
    }
}
