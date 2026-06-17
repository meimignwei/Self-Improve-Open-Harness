package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File read tool.
 * Java equivalent of Python's FileReadTool.
 */
public class FileReadTool extends BaseTool<FileReadTool.Input> {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2000;

    public FileReadTool() {
        super("read_file", "Read a text file from the local repository.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path filePath = resolvePath(context.cwd(), arguments.path());

        if (!Files.exists(filePath)) {
            return ToolResult.error("File not found: " + filePath);
        }
        if (Files.isDirectory(filePath)) {
            return ToolResult.error("Path is a directory: " + filePath);
        }
        if (!Files.isReadable(filePath)) {
            return ToolResult.error("File is not readable: " + filePath);
        }

        // Binary file detection
        try {
            byte[] raw = Files.readAllBytes(filePath);
            for (byte b : raw) {
                if (b == 0) {
                    return ToolResult.error("File appears to be a binary file: " + filePath);
                }
            }
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }

        try {
            int effectiveLimit = Math.min(arguments.limit(), MAX_LIMIT);
            java.util.List<String> allLines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            int fromIndex = arguments.offset();
            int toIndex = Math.min(fromIndex + effectiveLimit, allLines.size());

            if (fromIndex >= allLines.size()) {
                return ToolResult.success("(no content in selected range for " + filePath + ")");
            }

            java.util.List<String> selected = allLines.subList(fromIndex, toIndex);
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < selected.size(); i++) {
                result.append(String.format("%6d\t%s%n", arguments.offset() + i + 1, selected.get(i)));
            }
            return ToolResult.success(result.toString().stripTrailing());
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    /**
     * Resolve a path, supporting ~ expansion and relative paths.
     */
    private static Path resolvePath(Path cwd, String candidate) {
        String expanded = candidate;
        if (expanded.startsWith("~")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) {
            path = cwd.resolve(path);
        }
        return path.normalize();
    }

    public record Input(String path, int offset, int limit) {
        public Input {
            if (path == null) throw new IllegalArgumentException("path is required");
            if (offset < 0) offset = 0;
            if (limit <= 0) limit = DEFAULT_LIMIT;
            if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        }
    }
}
