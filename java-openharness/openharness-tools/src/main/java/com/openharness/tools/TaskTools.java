package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.task.TaskManager;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

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
            super("task_create", "Create a background shell or local-agent task.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            String type = args.type() != null ? args.type() : "local_bash";

            if ("local_bash".equals(type)) {
                if (args.command() == null || args.command().isBlank()) {
                    return ToolResult.error("command is required for local_bash tasks");
                }
                TaskManager.TaskRecord record = taskManager.createShellTask(
                        args.command(), args.description(), ctx.cwd(), Map.of());
                return ToolResult.success("Created task " + record.id() + " (" + record.type() + ")");
            } else if ("local_agent".equals(type) || "subagent".equals(type)) {
                if (args.prompt() == null || args.prompt().isBlank()) {
                    return ToolResult.error("prompt is required for local_agent tasks");
                }
                try {
                    TaskManager.TaskRecord record = taskManager.createAgentTask(
                            args.prompt(), args.description(), ctx.cwd());
                    return ToolResult.success("Created task " + record.id() + " (" + record.type() + ")");
                } catch (Exception e) {
                    return ToolResult.error(e.getMessage());
                }
            } else {
                return ToolResult.error("unsupported task type: " + type);
            }
        }

        @Override
        public boolean isReadOnly(Input arguments) { return false; }

        public record Input(String type, String description, String command, String prompt) {}
    }

    // --- TaskListTool ---

    public static class TaskListTool extends BaseTool<TaskListTool.Input> {
        private final TaskManager taskManager;

        public TaskListTool(TaskManager taskManager) {
            super("task_list", "List background tasks.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            List<TaskManager.TaskRecord> tasks;
            if (args.status() != null) {
                try {
                    TaskManager.TaskStatus status = TaskManager.TaskStatus.valueOf(args.status().toUpperCase());
                    tasks = taskManager.listTasks(status);
                } catch (IllegalArgumentException e) {
                    return ToolResult.error("Invalid status filter: " + args.status());
                }
            } else {
                tasks = taskManager.listTasks();
            }
            if (tasks.isEmpty()) {
                return ToolResult.success("(no tasks)");
            }
            StringBuilder sb = new StringBuilder();
            for (TaskManager.TaskRecord t : tasks) {
                sb.append(t.id()).append(" ").append(t.type()).append(" ")
                        .append(t.status()).append(" ").append(t.description()).append("\n");
            }
            return ToolResult.success(sb.toString().stripTrailing());
        }

        @Override
        public boolean isReadOnly(Input arguments) { return true; }

        public record Input(String status) {}
    }

    // --- TaskGetTool ---

    public static class TaskGetTool extends BaseTool<TaskGetTool.Input> {
        private final TaskManager taskManager;

        public TaskGetTool(TaskManager taskManager) {
            super("task_get", "Get details for a background task.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            return taskManager.getTask(args.taskId())
                    .map(t -> ToolResult.success(
                            t.id() + "\t" + t.type() + "\t" + t.status() + "\t" + t.description()))
                    .orElse(ToolResult.error("No task found with ID: " + args.taskId()));
        }

        @Override
        public boolean isReadOnly(Input arguments) { return true; }

        public record Input(String taskId) {}
    }

    // --- TaskOutputTool ---

    public static class TaskOutputTool extends BaseTool<TaskOutputTool.Input> {
        private final TaskManager taskManager;

        public TaskOutputTool(TaskManager taskManager) {
            super("task_output", "Read the output log for a background task.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            try {
                String output = taskManager.readTaskOutput(args.taskId(), args.maxBytes());
                return ToolResult.success(output != null && !output.isEmpty() ? output : "(no output)");
            } catch (Exception e) {
                return ToolResult.error(e.getMessage());
            }
        }

        @Override
        public boolean isReadOnly(Input arguments) { return true; }

        public record Input(String taskId, int maxBytes) {
            public Input {
                if (maxBytes < 1) maxBytes = 12000;
                if (maxBytes > 100000) maxBytes = 100000;
            }
            public Input(String taskId) {
                this(taskId, 12000);
            }
        }
    }

    // --- TaskStopTool ---

    public static class TaskStopTool extends BaseTool<TaskStopTool.Input> {
        private final TaskManager taskManager;

        public TaskStopTool(TaskManager taskManager) {
            super("task_stop", "Stop a background task.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            try {
                TaskManager.TaskRecord task = taskManager.stopTask(args.taskId());
                return ToolResult.success("Stopped task " + task.id());
            } catch (Exception e) {
                return ToolResult.error(e.getMessage());
            }
        }

        @Override
        public boolean isReadOnly(Input arguments) { return false; }

        public record Input(String taskId) {}
    }

    // --- TaskUpdateTool ---

    public static class TaskUpdateTool extends BaseTool<TaskUpdateTool.Input> {
        private final TaskManager taskManager;

        public TaskUpdateTool(TaskManager taskManager) {
            super("task_update", "Update a task description, progress, or status note.", Input.class);
            this.taskManager = taskManager;
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            try {
                TaskManager.TaskRecord task = taskManager.updateTask(
                        args.taskId(), args.description(), args.progress(), args.statusNote());

                StringBuilder sb = new StringBuilder("Updated task ").append(task.id());
                if (args.description() != null) {
                    sb.append(" description=").append(task.description());
                }
                if (args.progress() != null) {
                    Object progress = task.metadata().get("progress");
                    sb.append(" progress=").append(progress != null ? progress : "").append("%");
                }
                if (args.statusNote() != null) {
                    Object note = task.metadata().get("status_note");
                    sb.append(" note=").append(note != null ? note : "");
                }
                return ToolResult.success(sb.toString());
            } catch (Exception e) {
                return ToolResult.error(e.getMessage());
            }
        }

        @Override
        public boolean isReadOnly(Input arguments) { return false; }

        public record Input(String taskId, String description, Integer progress, String statusNote) {
            public Input {
                if (progress != null && (progress < 0 || progress > 100)) {
                    throw new IllegalArgumentException("progress must be between 0 and 100");
                }
            }
        }
    }
}
