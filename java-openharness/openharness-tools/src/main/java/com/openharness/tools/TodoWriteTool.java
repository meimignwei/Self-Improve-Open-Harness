package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Add or update an item in a TODO markdown checklist file.
 * Java equivalent of Python's todo_write_tool.
 * <p>
 * Reads/writes a TODO.md file on disk. Handles:
 * <ul>
 *   <li>New items — appends to the file</li>
 *   <li>Existing unchecked items marked checked — in-place toggle</li>
 *   <li>Already-in-desired-state items — no-op</li>
 * </ul>
 */
public class TodoWriteTool extends BaseTool<TodoWriteTool.Input> {

    public TodoWriteTool() {
        super("todo_write",
                "Add a new TODO item or mark an existing one as done in a markdown checklist file.",
                Input.class);
    }

    @Override
    public ToolResult execute(Input args, ToolExecutionContext ctx) {
        Path filePath = ctx.cwd().resolve(args.path());
        String existing;
        if (Files.exists(filePath)) {
            try {
                existing = Files.readString(filePath);
            } catch (IOException e) {
                return ToolResult.error("Failed to read TODO file: " + e.getMessage());
            }
        } else {
            existing = "# TODO\n";
            // Ensure parent directories exist
            try {
                Files.createDirectories(filePath.getParent());
            } catch (IOException ignored) {
                // best-effort
            }
        }

        String uncheckedLine = "- [ ] " + args.item();
        String checkedLine = "- [x] " + args.item();
        String targetLine = args.checked() ? checkedLine : uncheckedLine;

        if (args.checked() && existing.contains(uncheckedLine)) {
            // Mark existing unchecked item as done (in-place update)
            String updated = replaceFirst(existing, uncheckedLine, checkedLine);
            try {
                Files.writeString(filePath, updated);
            } catch (IOException e) {
                return ToolResult.error("Failed to write TODO file: " + e.getMessage());
            }
            return ToolResult.success("Checked TODO item: " + args.item());
        } else if (!args.checked() && existing.contains(checkedLine)) {
            // Uncheck an existing checked item
            String updated = replaceFirst(existing, checkedLine, uncheckedLine);
            try {
                Files.writeString(filePath, updated);
            } catch (IOException e) {
                return ToolResult.error("Failed to write TODO file: " + e.getMessage());
            }
            return ToolResult.success("Unchecked TODO item: " + args.item());
        } else if (existing.contains(targetLine)) {
            // Item already in desired state — no-op
            return ToolResult.success("No change needed in " + filePath);
        } else {
            // New item — append
            String updated = existing.stripTrailing() + "\n" + targetLine + "\n";
            try {
                Files.writeString(filePath, updated);
            } catch (IOException e) {
                return ToolResult.error("Failed to write TODO file: " + e.getMessage());
            }
            return ToolResult.success("Added TODO item: " + args.item());
        }
    }

    @Override
    public boolean isReadOnly(Input args) {
        return false;
    }

    /**
     * Replace the first occurrence of {@code oldStr} in {@code source}.
     */
    private static String replaceFirst(String source, String oldStr, String newStr) {
        int idx = source.indexOf(oldStr);
        if (idx < 0) return source;
        return source.substring(0, idx) + newStr + source.substring(idx + oldStr.length());
    }

    /**
     * Input for a single TODO item update.
     * Python equivalent: TodoWriteToolInput — 'item: str', 'checked: bool', 'path: str'.
     */
    public record Input(String item, boolean checked, String path) {
        public Input {
            if (item == null) throw new IllegalArgumentException("item is required");
            if (path == null) path = "TODO.md";
        }
    }
}
