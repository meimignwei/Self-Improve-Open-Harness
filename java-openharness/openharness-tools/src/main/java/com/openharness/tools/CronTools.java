package com.openharness.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;
import com.openharness.config.AtomicFileWriter;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cron job management tools.
 * Java equivalents of Python's CronCreate, CronDelete, CronList, CronToggle.
 */
public final class CronTools {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    private CronTools() {}

    // --- Validation helpers ---

    /**
     * Validates a 5-field cron expression (minute hour day month weekday).
     */
    static boolean validateCronExpression(String expression) {
        if (expression == null || expression.isBlank()) return false;
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) return false;
        for (String field : fields) {
            if (!field.matches("[0-9*/,\\-]+")) return false;
        }
        return true;
    }

    /**
     * Validates a timezone string using java.time.ZoneId.
     */
    static boolean validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) return true; // null/empty is allowed
        try {
            ZoneId.of(timezone);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Simple check whether the scheduler is running (stub — always returns false
     * unless an external scheduler implementation is plugged in).
     */
    static boolean isSchedulerRunning() {
        return false;
    }

    // --- CronCreateTool ---

    public static class CronCreateTool extends BaseTool<CronCreateTool.Input> {
        public CronCreateTool() {
            super("cron_create",
                    "Create or replace a local cron job with a standard cron expression. " +
                    "Use 'oh cron start' to run the scheduler daemon.",
                    Input.class);
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            if (!validateCronExpression(args.schedule())) {
                return ToolResult.error(
                        "Invalid cron expression: '" + args.schedule() + "'\n" +
                        "Use standard 5-field format: minute hour day month weekday\n" +
                        "Examples: '*/5 * * * *' (every 5 min), '0 9 * * 1-5' (weekdays 9am)");
            }
            if (!validateTimezone(args.timezone())) {
                return ToolResult.error("Invalid timezone: '" + args.timezone() + "'");
            }
            if ((args.command() == null || args.command().isBlank())
                    && (args.message() == null || args.message().isBlank())) {
                return ToolResult.error("Cron job requires command or message.");
            }

            Path registryPath = ctx.cwd().resolve(".openharness").resolve("cron_jobs.json");
            List<CronJob> jobs = load(registryPath);

            // Remove existing job with the same name (upsert behavior)
            jobs.removeIf(j -> j.name().equals(args.name()));

            CronJob job = new CronJob(
                    args.name(),
                    args.schedule(),
                    args.command(),
                    args.message(),
                    args.timezone() != null ? args.timezone() : "Asia/Shanghai",
                    args.enabled(),
                    ctx.cwd().toString(),
                    null,    // lastRun
                    null,    // nextRun
                    null     // lastStatus
            );

            jobs.add(job);
            save(registryPath, jobs);
            String status = args.enabled() ? "enabled" : "disabled";
            return ToolResult.success(
                    "Created cron job '" + args.name() + "' [" + args.schedule() + "] (" + status + ")");
        }

        @Override public boolean isReadOnly(Input args) { return false; }

        public record Input(String schedule, String name, String command, String message,
                           String timezone, boolean enabled) {
            public Input {
                if (schedule == null || schedule.isBlank())
                    throw new IllegalArgumentException("schedule is required");
                if (name == null || name.isBlank())
                    throw new IllegalArgumentException("name is required");
                if (timezone == null || timezone.isBlank())
                    timezone = "Asia/Shanghai";
            }
        }
    }

    // --- CronDeleteTool ---

    public static class CronDeleteTool extends BaseTool<CronDeleteTool.Input> {
        public CronDeleteTool() {
            super("cron_delete", "Delete a local cron-style job by name.", Input.class);
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            Path registryPath = ctx.cwd().resolve(".openharness").resolve("cron_jobs.json");
            List<CronJob> jobs = load(registryPath);
            boolean removed = jobs.removeIf(j -> j.name().equals(args.name()));
            if (!removed) return ToolResult.error("Cron job not found: " + args.name());
            save(registryPath, jobs);
            return ToolResult.success("Deleted cron job " + args.name());
        }

        @Override public boolean isReadOnly(Input args) { return false; }

        public record Input(String name) {
            public Input {
                if (name == null || name.isBlank())
                    throw new IllegalArgumentException("name is required");
            }
        }
    }

    // --- CronListTool ---

    public static class CronListTool extends BaseTool<Void> {
        public CronListTool() { super("cron_list",
                "List configured local cron jobs with schedule, status, and next run time.", Void.class); }

        @Override
        public ToolResult execute(Void args, ToolExecutionContext ctx) {
            Path registryPath = ctx.cwd().resolve(".openharness").resolve("cron_jobs.json");
            List<CronJob> jobs = load(registryPath);
            if (jobs.isEmpty()) return ToolResult.success("No cron jobs configured.");

            String scheduler = isSchedulerRunning() ? "running" : "stopped";
            StringBuilder sb = new StringBuilder("Scheduler: ").append(scheduler).append("\n\n");

            for (CronJob j : jobs) {
                String enabled = j.enabled() ? "on" : "off";
                String lastRun = j.lastRun() != null ? j.lastRun() : "never";
                if (lastRun.length() > 19) lastRun = lastRun.substring(0, 19);
                String nextRun = j.nextRun() != null ? j.nextRun() : "n/a";
                if (nextRun.length() > 19) nextRun = nextRun.substring(0, 19);
                String lastStatus = j.lastStatus() != null ? j.lastStatus() : "";
                String statusStr = !lastStatus.isEmpty() ? " (" + lastStatus + ")" : "";
                String timezone = j.timezone() != null ? " (" + j.timezone() + ")" : "";
                String command = j.command() != null ? j.command() : "(agent_turn)";

                sb.append("[").append(enabled).append("] ").append(j.name())
                        .append("  ").append(j.schedule()).append(timezone).append("\n")
                        .append("     cmd: ").append(command).append("\n")
                        .append("     last: ").append(lastRun).append(statusStr)
                        .append("  next: ").append(nextRun).append("\n");
            }
            return ToolResult.success(sb.toString());
        }

        @Override public boolean isReadOnly(Void args) { return true; }
    }

    // --- CronToggleTool ---

    public static class CronToggleTool extends BaseTool<CronToggleTool.Input> {
        public CronToggleTool() {
            super("cron_toggle", "Enable or disable a local cron job by name.", Input.class);
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            Path registryPath = ctx.cwd().resolve(".openharness").resolve("cron_jobs.json");
            List<CronJob> jobs = load(registryPath);
            boolean found = false;
            for (int i = 0; i < jobs.size(); i++) {
                CronJob j = jobs.get(i);
                if (j.name().equals(args.name())) {
                    found = true;
                    jobs.set(i, new CronJob(j.name(), j.schedule(), j.command(), j.message(),
                            j.timezone(), args.enabled(), j.cwd(),
                            j.lastRun(), j.nextRun(), j.lastStatus()));
                    break;
                }
            }
            if (!found) return ToolResult.error("Cron job not found: " + args.name());
            save(registryPath, jobs);
            String state = args.enabled() ? "enabled" : "disabled";
            return ToolResult.success("Cron job '" + args.name() + "' is now " + state);
        }

        @Override public boolean isReadOnly(Input args) { return false; }

        public record Input(String name, boolean enabled) {
            public Input {
                if (name == null || name.isBlank())
                    throw new IllegalArgumentException("name is required");
            }
        }
    }

    // --- Persistence helpers ---

    @SuppressWarnings("unchecked")
    private static List<CronJob> load(Path path) {
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            return MAPPER.readValue(path.toFile(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, CronJob.class));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void save(Path path, List<CronJob> jobs) {
        AtomicFileWriter.writeJson(path, jobs);
    }

    // --- CronJob record ---

    public record CronJob(String name, String schedule, String command, String message,
                           String timezone, boolean enabled, String cwd,
                           String lastRun, String nextRun, String lastStatus) {}
}
