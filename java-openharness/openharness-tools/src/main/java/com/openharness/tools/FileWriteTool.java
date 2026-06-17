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
        super("write_file", "Create or overwrite a text file in the local repository.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path filePath = resolvePath(context.cwd(), arguments.path());

        try {
            if (arguments.createDirectories()) {
                Path parent = filePath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
            }

            // Atomic write: write to temp file then rename
            Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(tempPath, arguments.content(), StandardCharsets.UTF_8);
            Files.move(tempPath, filePath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return ToolResult.success("Wrote " + filePath);

        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
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

    public record Input(String path, String content, boolean createDirectories) {
        public Input {
            if (path == null) throw new IllegalArgumentException("path is required");
            if (content == null) content = "";
        }

        public Input(String path, String content) {
            this(path, content, true);
        }
    }
}
