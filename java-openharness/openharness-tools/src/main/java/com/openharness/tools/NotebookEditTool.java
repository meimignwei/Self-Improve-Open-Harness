package com.openharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Create or edit a Jupyter notebook (.ipynb) cell without requiring nbformat.
 * Java equivalent of Python's NotebookEditTool.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code replace} — replace the cell source (Python default)</li>
 *   <li>{@code append} — append to existing cell source (Python mode)</li>
 *   <li>{@code insert} — insert a new cell at the given index (Java extension)</li>
 *   <li>{@code delete} — delete the cell at the given index (Java extension)</li>
 * </ul>
 *
 * <p>Source format: stored as a JSON array of strings (Jupyter v4+ nbformat),
 * matching the standard format. Python stores source as a single string
 * (Jupyter v3 format), which is less standard but simpler for LLM interaction.
 */
public class NotebookEditTool extends BaseTool<NotebookEditTool.Input> {

    public NotebookEditTool() {
        super("notebook_edit", "Create or edit a Jupyter notebook cell.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path filePath = context.cwd().resolve(arguments.path()).normalize();

        if (!Files.exists(filePath)) {
            if (!arguments.createIfMissing()) {
                return ToolResult.error("Notebook not found: " + filePath);
            }
            // Create empty notebook template (matching Python)
            try {
                createEmptyNotebook(filePath);
            } catch (IOException e) {
                return ToolResult.error("Failed to create notebook: " + e.getMessage());
            }
        }

        try {
            ObjectMapper mapper = OpenHarnessObjectMapper.get();
            JsonNode notebook = mapper.readTree(filePath.toFile());

            ArrayNode cells = (ArrayNode) notebook.get("cells");
            if (cells == null) {
                return ToolResult.error("Invalid notebook: missing 'cells' array");
            }

            // Auto-fill cells if cellNumber is beyond current count (matching Python)
            while (cells.size() <= arguments.cellNumber()) {
                cells.add(createEmptyCell(mapper, arguments.cellType()));
            }

            return switch (arguments.mode()) {
                case "replace" -> replaceCell(mapper, filePath, notebook, cells, arguments);
                case "append" -> appendCell(mapper, filePath, notebook, cells, arguments);
                case "insert" -> insertCell(mapper, filePath, notebook, cells, arguments);
                case "delete" -> deleteCell(mapper, filePath, notebook, cells, arguments);
                default -> ToolResult.error("Unknown mode: " + arguments.mode()
                        + ". Use replace, append, insert, or delete.");
            };
        } catch (IOException e) {
            return ToolResult.error("Failed to edit notebook: " + e.getMessage());
        }
    }

    private ToolResult replaceCell(ObjectMapper mapper, Path path, JsonNode notebook,
                                    ArrayNode cells, Input args) throws IOException {
        int idx = args.cellNumber();
        JsonNode existingCell = cells.get(idx);
        if (existingCell == null) {
            return ToolResult.error("Cell not found at index " + idx);
        }

        // Preserve cell id if present
        String cellId = existingCell.has("id") ? existingCell.get("id").asText()
                : java.util.UUID.randomUUID().toString().substring(0, 8);

        ObjectNode newCell = createCell(mapper, cellId, args.newSource(), args.cellType());
        // Preserve existing outputs for code cells
        if ("code".equals(args.cellType()) && existingCell.has("outputs")) {
            newCell.set("outputs", existingCell.get("outputs"));
        }
        cells.set(idx, newCell);
        writeNotebook(mapper, path, notebook);
        return ToolResult.success("Updated notebook cell " + idx + " in " + path);
    }

    private ToolResult appendCell(ObjectMapper mapper, Path path, JsonNode notebook,
                                   ArrayNode cells, Input args) throws IOException {
        int idx = args.cellNumber();
        JsonNode existingCell = cells.get(idx);
        if (existingCell == null) {
            return ToolResult.error("Cell not found at index " + idx);
        }

        // Normalize existing source to a single string
        String existingSource = normalizeSource(existingCell.get("source"));
        String combinedSource = existingSource + args.newSource();

        // Set cell type if different
        String cellType = args.cellType() != null ? args.cellType()
                : (existingCell.has("cell_type") ? existingCell.get("cell_type").asText() : "code");
        String cellId = existingCell.has("id") ? existingCell.get("id").asText()
                : java.util.UUID.randomUUID().toString().substring(0, 8);

        ObjectNode updatedCell = createCell(mapper, cellId, combinedSource, cellType);
        // Preserve existing outputs for code cells
        if (existingCell.has("outputs")) {
            updatedCell.set("outputs", existingCell.get("outputs"));
        }
        // Preserve execution_count if present
        if (existingCell.has("execution_count")) {
            updatedCell.set("execution_count", existingCell.get("execution_count"));
        }
        cells.set(idx, updatedCell);
        writeNotebook(mapper, path, notebook);
        return ToolResult.success("Appended to notebook cell " + idx + " in " + path);
    }

    /**
     * Java extension: insert a new cell at the given cellNumber.
     */
    private ToolResult insertCell(ObjectMapper mapper, Path path, JsonNode notebook,
                                   ArrayNode cells, Input args) throws IOException {
        int idx = args.cellNumber();
        // Insert before the target index; if at end, append
        if (idx < 0) idx = 0;
        if (idx > cells.size()) idx = cells.size();
        String cellId = java.util.UUID.randomUUID().toString().substring(0, 8);
        ObjectNode newCell = createCell(mapper, cellId, args.newSource(), args.cellType());
        cells.insert(idx, newCell);
        writeNotebook(mapper, path, notebook);
        return ToolResult.success("Inserted cell at position " + idx + " in " + path);
    }

    /**
     * Java extension: delete the cell at cellNumber.
     */
    private ToolResult deleteCell(ObjectMapper mapper, Path path, JsonNode notebook,
                                   ArrayNode cells, Input args) throws IOException {
        int idx = args.cellNumber();
        if (idx < 0 || idx >= cells.size()) {
            return ToolResult.error("Cell not found at index " + idx);
        }
        cells.remove(idx);
        writeNotebook(mapper, path, notebook);
        return ToolResult.success("Deleted cell " + idx + " from " + path);
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Normalize cell source from various formats to a single string.
     * Python stores source as a string; Jupyter v4+ stores it as a string array.
     */
    private static String normalizeSource(JsonNode source) {
        if (source == null || source.isNull()) {
            return "";
        }
        if (source.isTextual()) {
            return source.asText();
        }
        if (source.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : source) {
                sb.append(line.asText());
            }
            return sb.toString();
        }
        return source.asText();
    }

    private ObjectNode createCell(ObjectMapper mapper, String cellId, String source, String cellType) {
        ObjectNode cell = mapper.createObjectNode();
        cell.put("cell_type", cellType != null && !cellType.isBlank() ? cellType : "code");
        if (cellId != null && !cellId.isBlank()) {
            cell.put("id", cellId);
        }
        cell.putObject("metadata");

        // Store source as array (Jupyter v4+ standard format)
        // Python uses single string; we use array for standards compliance
        ArrayNode sourceArray = mapper.createArrayNode();
        if (source != null && !source.isEmpty()) {
            for (String line : source.split("\n", -1)) {
                sourceArray.add(line);
            }
        } else {
            sourceArray.add("");
        }
        cell.set("source", sourceArray);

        if ("code".equals(cellType)) {
            cell.putArray("outputs");
            cell.putNull("execution_count");
        }
        return cell;
    }

    private ObjectNode createEmptyCell(ObjectMapper mapper, String cellType) {
        return createCell(mapper,
                java.util.UUID.randomUUID().toString().substring(0, 8),
                "", cellType != null ? cellType : "code");
    }

    private void createEmptyNotebook(Path path) throws IOException {
        // Matching Python's template: {"cells":[], "metadata":{"language_info":{"name":"python"}},
        //   "nbformat":4, "nbformat_minor":5}
        ObjectMapper mapper = OpenHarnessObjectMapper.get();
        ObjectNode notebook = mapper.createObjectNode();
        notebook.putArray("cells");
        ObjectNode metadata = mapper.createObjectNode();
        ObjectNode languageInfo = mapper.createObjectNode();
        languageInfo.put("name", "python");
        metadata.set("language_info", languageInfo);
        notebook.set("metadata", metadata);
        notebook.put("nbformat", 4);
        notebook.put("nbformat_minor", 5);
        path.getParent().toFile().mkdirs();
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(notebook), StandardCharsets.UTF_8);
    }

    private void writeNotebook(ObjectMapper mapper, Path path, JsonNode notebook) throws IOException {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(notebook) + "\n";
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
    }

    public record Input(String path, int cellNumber, String newSource, String cellType,
                        String mode, boolean createIfMissing) {
        public Input {
            if (path == null) throw new IllegalArgumentException("path is required");
            if (newSource == null) newSource = "";
            if (cellType == null || cellType.isBlank()) cellType = "code";
            if (mode == null || mode.isBlank()) mode = "replace";
            // createIfMissing defaults to true via the record, but we validate here
        }

        /** Python-compatible constructor: createIfMissing defaults to true. */
        public Input(String path, int cellNumber, String newSource, String cellType, String mode) {
            this(path, cellNumber, newSource, cellType, mode, true);
        }

        /** Backward-compatible constructor matching old editMode parameter name. */
        @Deprecated
        public static Input withEditMode(String path, int cellNumber, String newSource,
                                          String cellType, String editMode) {
            return new Input(path, cellNumber, newSource, cellType,
                    editMode != null ? editMode : "replace", true);
        }
    }
}
