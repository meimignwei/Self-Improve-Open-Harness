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

    private static final int MAX_LINES_DEFAULT = 2000;

    public FileReadTool() {
        super("read", "Reads a file from the local filesystem.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path filePath = context.cwd().resolve(arguments.path()).normalize();

        if (!Files.exists(filePath)) {
            return ToolResult.error("File not found: " + filePath);
        }
        if (Files.isDirectory(filePath)) {
            return ToolResult.error("Path is a directory: " + filePath);
        }
        if (!Files.isReadable(filePath)) {
            return ToolResult.error("File is not readable: " + filePath);
        }

        try {
            String content = Files.lines(filePath, StandardCharsets.UTF_8)
                    .skip(arguments.offset())
                    .limit(arguments.limit() > 0 ? arguments.limit() : MAX_LINES_DEFAULT)
                    .collect(StringBuilder::new,
                            (sb, line) -> sb.append(line).append("\n"),
                            StringBuilder::append)
                    .toString();
            return ToolResult.success(content.isEmpty() ? "(empty file)" : content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    public record Input(String path, int offset, int limit) {
        public Input {
            if (path == null) throw new IllegalArgumentException("path is required");
            if (offset < 0) offset = 0;
            if (limit <= 0) limit = MAX_LINES_DEFAULT;
        }
    }
}
