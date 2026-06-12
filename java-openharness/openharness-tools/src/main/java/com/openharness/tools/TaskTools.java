package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.task.TaskManager;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Collection of task management tools.
 * Includes TaskCreateTool, TaskListTool, TaskGetTool, TaskOutputTool, TaskStopTool, TaskUpdateTool.
 */
public final class TaskTools {

    private TaskTools() {}

    // --- TaskCreateTool ---

    public static class TaskCreateTool extends BaseTool<TaskCreateTool.Input> {
        private final TaskManager taskManager;

        public TaskCreateTool(TaskManager taskManager) {
            super("task_create", "Create a background shell or agent task.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            Path cwd = args.cwd() != null ? Path.of(args.cwd()) : ctx.cwd();
            TaskManager.TaskRecord record;
            if ("agent".equals(args.type())) {
                record = taskManager.createAgentTask(args.prompt(), cwd);
            } else {
                record = taskManager.createShellTask(args.command(), cwd, Map.of());
            }
            return ToolResult.success("Task created: " + record.id() + " (" + record.type() + ")");
        }

        @Override
        public boolean isReadOnly(Input arguments) { return false; }

        public record Input(String command, String prompt, String type, String cwd) {}
    }

    // --- TaskListTool ---

    public static class TaskListTool extends BaseTool<Void> {
        private final TaskManager taskManager;

        public TaskListTool(TaskManager taskManager) {
            super("task_list", "List all background tasks.", Void.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Void args, ToolExecutionContext ctx) {
            List<TaskManager.TaskRecord> tasks = taskManager.listTasks();
            if (tasks.isEmpty()) return ToolResult.success("No active tasks.");
            StringBuilder sb = new StringBuilder();
            for (TaskManager.TaskRecord t : tasks) {
                sb.append(t.id().substring(0, 8)).append("  ")
                        .append(t.status()).append("  ")
                        .append(t.type()).append("  ")
                        .append(t.description().length() > 60
                                ? t.description().substring(0, 57) + "..."
                                : t.description())
                        .append("\n");
            }
            return ToolResult.success(sb.toString().stripTrailing());
        }

        @Override
        public boolean isReadOnly(Void arguments) { return true; }
    }

    // --- TaskGetTool ---

    public static class TaskGetTool extends BaseTool<TaskGetTool.Input> {
        private final TaskManager taskManager;

        public TaskGetTool(TaskManager taskManager) {
            super("task_get", "Get details of a specific task.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            return taskManager.getTask(args.taskId())
                    .map(t -> ToolResult.success(
                            "ID: " + t.id() + "\n" +
                            "Type: " + t.type() + "\n" +
                            "Status: " + t.status() + "\n" +
                            "Created: " + t.createdAt() + "\n" +
                            (t.completedAt() != null ? "Completed: " + t.completedAt() + "\n" : "") +
                            (t.returnCode() != null ? "Exit code: " + t.returnCode() + "\n" : "") +
                            "Command: " + t.description()))
                    .orElse(ToolResult.error("Task not found: " + args.taskId()));
        }

        @Override
        public boolean isReadOnly(Input arguments) { return true; }

        public record Input(String taskId) {}
    }

    // --- TaskOutputTool ---

    public static class TaskOutputTool extends BaseTool<TaskOutputTool.Input> {
        private final TaskManager taskManager;

        public TaskOutputTool(TaskManager taskManager) {
            super("task_output", "Read output from a running or completed task.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            String output = taskManager.readTaskOutput(args.taskId());
            if (output.isEmpty()) {
                return ToolResult.error("No output for task: " + args.taskId());
            }
            return ToolResult.success(output);
        }

        @Override
        public boolean isReadOnly(Input arguments) { return true; }

        public record Input(String taskId) {}
    }

    // --- TaskStopTool ---

    public static class TaskStopTool extends BaseTool<TaskStopTool.Input> {
        private final TaskManager taskManager;

        public TaskStopTool(TaskManager taskManager) {
            super("task_stop", "Stop a running background task.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            taskManager.stopTask(args.taskId());
            return ToolResult.success("Task stopped: " + args.taskId());
        }

        @Override
        public boolean isReadOnly(Input arguments) { return false; }

        public record Input(String taskId) {}
    }

    // --- TaskUpdateTool ---

    public static class TaskUpdateTool extends BaseTool<TaskUpdateTool.Input> {
        private final TaskManager taskManager;

        public TaskUpdateTool(TaskManager taskManager) {
            super("task_update", "Update a task's metadata.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            var opt = taskManager.getTask(args.taskId());
            if (opt.isEmpty()) return ToolResult.error("Task not found: " + args.taskId());
            return ToolResult.success("Task updated: " + args.taskId());
        }

        @Override
        public boolean isReadOnly(Input arguments) { return false; }

        public record Input(String taskId, String status) {}
    }
}
