package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File write tool with atomic write semantics.
 * Java equivalent of Python's FileWriteTool.
 */
public class FileWriteTool extends BaseTool<FileWriteTool.Input> {

    public FileWriteTool() {
        super("write", "Writes a file to the local filesystem.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path filePath = context.cwd().resolve(arguments.path()).normalize();

        try {
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Atomic write: write to temp file then rename
            Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(tempPath, arguments.content(), StandardCharsets.UTF_8);
            Files.move(tempPath, filePath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            long size = Files.size(filePath);
            return ToolResult.success("Wrote " + size + " bytes to " + filePath);

        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
    }

    public record Input(String path, String content) {
        public Input {
            if (path == null) throw new IllegalArgumentException("path is required");
            if (content == null) content = "";
        }
    }
}
