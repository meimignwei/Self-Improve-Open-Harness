package com.openharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.util.List;

/**
 * Create and manage a structured task list.
 * Java equivalent of Python's todo_write.
 */
public class TodoWriteTool extends BaseTool<TodoWriteTool.Input> {

    public TodoWriteTool() {
        super("todo_write", "Create and manage a structured task list for your current session.", Input.class);
    }

    @Override
    public ToolResult execute(Input args, ToolExecutionContext ctx) {
        if (args.todos() == null || args.todos().isEmpty()) {
            return ToolResult.error("At least one todo item is required.");
        }

        StringBuilder sb = new StringBuilder("## Task List\n\n");
        int completed = 0;
        for (int i = 0; i < args.todos().size(); i++) {
            TodoItem item = args.todos().get(i);
            String marker = "completed".equals(item.status()) ? "[x]" : "[ ]";
            if ("completed".equals(item.status())) completed++;
            sb.append("- ").append(marker).append(" **").append(item.subject()).append("**");
            if (item.description() != null && !item.description().isEmpty()) {
                sb.append(" — ").append(item.description());
            }
            sb.append("\n");
        }

        int total = args.todos().size();
        sb.append("\n*").append(completed).append("/").append(total).append(" tasks completed*");
        return ToolResult.success(sb.toString());
    }

    @Override public boolean isReadOnly(Input args) { return false; }

    public record Input(List<TodoItem> todos) {
        public Input { if (todos == null) throw new IllegalArgumentException("todos is required"); }
    }

    public record TodoItem(String subject, String description, String status, int priority) {
        public TodoItem {
            if (subject == null) throw new IllegalArgumentException("subject is required");
            if (status == null) status = "pending";
        }
    }
}
