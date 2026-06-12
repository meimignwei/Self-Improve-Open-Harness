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
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Lightweight code intelligence tool (LSP wrapper).
 * Currently supports Python files via pylsp when available.
 */
public class LspTool extends BaseTool<LspTool.Input> {

    private static final Logger LOG = Logger.getLogger(LspTool.class.getName());
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    public LspTool() {
        super("lsp", "Read-only code intelligence: document_symbol, workspace_symbol, go_to_definition, find_references, hover.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        String op = arguments.operation();
        Path root = context.cwd();

        if ("workspace_symbol".equals(op)) {
            return ToolResult.error("workspace_symbol requires a running LSP server. Not yet fully implemented in Java.");
        }

        if (arguments.filePath() == null) {
            return ToolResult.error("file_path is required for operation: " + op);
        }
        Path filePath = root.resolve(arguments.filePath()).normalize();
        if (!Files.exists(filePath)) {
            return ToolResult.error("File not found: " + filePath);
        }

        switch (op) {
            case "document_symbol" -> {
                return runPylsp(root, filePath, "textDocument/documentSymbol",
                        MAPPER.createObjectNode().set("textDocument",
                                MAPPER.createObjectNode().put("uri", filePath.toUri().toString())));
            }
            case "hover" -> {
                if (arguments.line() == null || arguments.line() <= 0) return ToolResult.error("line is required for hover");
                ObjectNode pos = MAPPER.createObjectNode();
                pos.put("line", arguments.line() - 1);
                pos.put("character", arguments.character() != null ? arguments.character() - 1 : 0);
                ObjectNode params = MAPPER.createObjectNode();
                params.set("textDocument", MAPPER.createObjectNode().put("uri", filePath.toUri().toString()));
                params.set("position", pos);
                return runPylsp(root, filePath, "textDocument/hover", params);
            }
            case "go_to_definition" -> {
                if (arguments.line() == null || arguments.line() <= 0) return ToolResult.error("line is required for go_to_definition");
                ObjectNode pos = MAPPER.createObjectNode();
                pos.put("line", arguments.line() - 1);
                pos.put("character", arguments.character() != null ? arguments.character() - 1 : 0);
                ObjectNode params = MAPPER.createObjectNode();
                params.set("textDocument", MAPPER.createObjectNode().put("uri", filePath.toUri().toString()));
                params.set("position", pos);
                return runPylsp(root, filePath, "textDocument/definition", params);
            }
            default -> {
                return ToolResult.error("Unknown LSP operation: " + op);
            }
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    private ToolResult runPylsp(Path root, Path filePath, String method, JsonNode params) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python", "-m", "pylsp");
            pb.directory(root.toFile());
            Process process = pb.start();

            // Send initialize
            String init = MAPPER.writeValueAsString(jsonRpc("initialize", MAPPER.createObjectNode()
                    .put("processId", process.pid())
                    .set("capabilities", MAPPER.createObjectNode()))) + "\n";
            process.getOutputStream().write(init.getBytes());
            process.getOutputStream().flush();

            // Send request
            String req = MAPPER.writeValueAsString(jsonRpc(method, params)) + "\n";
            process.getOutputStream().write(req.getBytes());
            process.getOutputStream().flush();

            // Read response
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (line.contains("\"result\"")) break;
                }
            }

            process.destroyForcibly();
            return ToolResult.success(output.toString().stripTrailing());
        } catch (Exception e) {
            return ToolResult.error("LSP call failed. Ensure pylsp is installed (pip install python-lsp-server). " + e.getMessage());
        }
    }

    private JsonNode jsonRpc(String method, JsonNode params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", java.util.UUID.randomUUID().toString());
        node.put("method", method);
        node.set("params", params);
        return node;
    }

    public record Input(String operation, String filePath, String symbol,
                        Integer line, Integer character, String query) {
        public Input {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("operation is required");
            }
        }
    }
}
