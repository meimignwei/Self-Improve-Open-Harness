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
 * Edits Jupyter notebook (.ipynb) cells.
 * Java equivalent of Python's NotebookEditTool.
 */
public class NotebookEditTool extends BaseTool<NotebookEditTool.Input> {

    public NotebookEditTool() {
        super("notebook_edit", "Edit a cell in a Jupyter notebook (.ipynb).", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path filePath = context.cwd().resolve(arguments.path()).normalize();

        if (!Files.exists(filePath)) {
            return ToolResult.error("Notebook not found: " + filePath);
        }

        try {
            ObjectMapper mapper = OpenHarnessObjectMapper.get();
            JsonNode notebook = mapper.readTree(filePath.toFile());

            ArrayNode cells = (ArrayNode) notebook.get("cells");
            if (cells == null) {
                return ToolResult.error("Invalid notebook: missing 'cells' array");
            }

            return switch (arguments.editMode()) {
                case "replace" -> replaceCell(mapper, filePath, notebook, cells, arguments);
                case "insert" -> insertCell(mapper, filePath, notebook, cells, arguments);
                case "delete" -> deleteCell(mapper, filePath, notebook, cells, arguments);
                default -> ToolResult.error("Unknown edit_mode: " + arguments.editMode()
                        + ". Use replace, insert, or delete.");
            };
        } catch (IOException e) {
            return ToolResult.error("Failed to edit notebook: " + e.getMessage());
        }
    }

    private ToolResult replaceCell(ObjectMapper mapper, Path path, JsonNode notebook,
                                    ArrayNode cells, Input args) throws IOException {
        int idx = args.cellId() != null ? findCellById(cells, args.cellId()) : args.cellNumber();
        if (idx < 0 || idx >= cells.size()) {
            return ToolResult.error("Cell not found: " + args.cellId());
        }
        ObjectNode newCell = createCell(mapper, args.newSource(), args.cellType());
        cells.set(idx, newCell);
        writeNotebook(mapper, path, notebook);
        return ToolResult.success("Replaced cell " + idx + " in " + path);
    }

    private ToolResult insertCell(ObjectMapper mapper, Path path, JsonNode notebook,
                                   ArrayNode cells, Input args) throws IOException {
        int idx = args.cellId() != null ? findCellById(cells, args.cellId()) + 1 : cells.size();
        if (idx < 0) idx = 0;
        if (idx > cells.size()) idx = cells.size();
        ObjectNode newCell = createCell(mapper, args.newSource(), args.cellType());
        cells.insert(idx, newCell);
        writeNotebook(mapper, path, notebook);
        return ToolResult.success("Inserted cell at position " + idx + " in " + path);
    }

    private ToolResult deleteCell(ObjectMapper mapper, Path path, JsonNode notebook,
                                   ArrayNode cells, Input args) throws IOException {
        int idx = args.cellId() != null ? findCellById(cells, args.cellId()) : args.cellNumber();
        if (idx < 0 || idx >= cells.size()) {
            return ToolResult.error("Cell not found: " + args.cellId());
        }
        cells.remove(idx);
        writeNotebook(mapper, path, notebook);
        return ToolResult.success("Deleted cell " + idx + " from " + path);
    }

    private int findCellById(ArrayNode cells, String cellId) {
        for (int i = 0; i < cells.size(); i++) {
            JsonNode cell = cells.get(i);
            if (cell.has("id") && cell.get("id").asText().equals(cellId)) {
                return i;
            }
        }
        return -1;
    }

    private ObjectNode createCell(ObjectMapper mapper, String source, String cellType) {
        ObjectNode cell = mapper.createObjectNode();
        cell.put("cell_type", cellType != null ? cellType : "code");
        cell.put("id", java.util.UUID.randomUUID().toString().substring(0, 8));
        cell.putObject("metadata");

        ArrayNode sourceArray = mapper.createArrayNode();
        for (String line : source.lines().toList()) {
            sourceArray.add(line);
        }
        cell.set("source", sourceArray);
        return cell;
    }

    private void writeNotebook(ObjectMapper mapper, Path path, JsonNode notebook) throws IOException {
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tempPath, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(notebook), StandardCharsets.UTF_8);
        Files.move(tempPath, path,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
    }

    public record Input(String path, String cellId, int cellNumber,
                        String newSource, String cellType, String editMode) {
        public Input {
            if (path == null) throw new IllegalArgumentException("path is required");
            if (newSource == null) newSource = "";
            if (editMode == null) editMode = "replace";
        }
    }
}
