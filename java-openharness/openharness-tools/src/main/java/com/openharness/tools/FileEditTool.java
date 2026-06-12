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
        super("edit", "Performs exact string replacements in an existing file.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path filePath = context.cwd().resolve(arguments.path()).normalize();

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
                return ToolResult.error("old_string not found in file: " + filePath);
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

            String msg = arguments.replaceAll()
                    ? "Replaced " + count + " occurrence(s) in " + filePath
                    : "Replaced 1 occurrence in " + filePath;
            return ToolResult.success(msg);

        } catch (IOException e) {
            return ToolResult.error("Failed to edit file: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
    }

    public record Input(String path, String oldString, String newString, boolean replaceAll) {
        public Input {
            if (path == null) throw new IllegalArgumentException("path is required");
            if (oldString == null) throw new IllegalArgumentException("old_string is required");
            if (newString == null) newString = "";
        }
    }
}
