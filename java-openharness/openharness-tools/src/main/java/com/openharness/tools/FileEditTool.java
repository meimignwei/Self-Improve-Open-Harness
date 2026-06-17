package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File edit tool using exact string replacement.
 * Java equivalent of Python's FileEditTool.
 */
public class FileEditTool extends BaseTool<FileEditTool.Input> {

    public FileEditTool() {
        super("edit_file", "Edit an existing file by replacing a string.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path filePath = resolvePath(context.cwd(), arguments.path());

        if (!Files.exists(filePath)) {
            return ToolResult.error("File not found: " + filePath);
        }

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String oldString = arguments.oldString();
            String newString = arguments.newString();

            // Count occurrences
            int count = 0;
            int idx = 0;
            while ((idx = content.indexOf(oldString, idx)) != -1) {
                count++;
                idx += oldString.length();
            }

            if (count == 0) {
                return ToolResult.error("old_string was not found in the file");
            }

            if (arguments.replaceAll()) {
                content = content.replace(oldString, newString);
            } else {
                if (count > 1) {
                    return ToolResult.error(
                            "old_string appears " + count + " times in the file. "
                                    + "Use replace_all=true to replace all occurrences, "
                                    + "or provide a more specific string to make a single match.");
                }
                content = content.replace(oldString, newString);
            }

            // Atomic write
            Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(tempPath, content, StandardCharsets.UTF_8);
            Files.move(tempPath, filePath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return ToolResult.success("Updated " + filePath);

        } catch (IOException e) {
            return ToolResult.error("Failed to edit file: " + e.getMessage());
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

    public record Input(String path, String oldString, String newString, boolean replaceAll) {
        public Input {
            if (path == null) throw new IllegalArgumentException("path is required");
            if (oldString == null) throw new IllegalArgumentException("old_string is required");
            if (newString == null) newString = "";
        }
    }
}
