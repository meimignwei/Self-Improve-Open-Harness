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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cron job management tools.
 * Java equivalents of Python's CronCreate, CronDelete, CronList.
 */
public final class CronTools {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    public static class CronCreateTool extends BaseTool<CronCreateTool.Input> {
        public CronCreateTool() {
            super("cron_create", "Schedule a recurring or one-shot cron job.", Input.class);
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            Path registryPath = ctx.cwd().resolve(".openharness").resolve("cron_jobs.json");
            List<CronJob> jobs = load(registryPath);

            CronJob job = new CronJob(
                    UUID.randomUUID().toString(), args.cron(), args.prompt(),
                    args.enabled(), args.timezone() != null ? args.timezone() : "UTC",
                    args.description(), false, null, Instant.now());

            jobs.add(job);
            save(registryPath, jobs);
            return ToolResult.success("Cron job created: " + job.id() + " (" + args.cron() + ")");
        }

        @Override public boolean isReadOnly(Input args) { return false; }

        public record Input(String cron, String prompt, String description,
                           String timezone, boolean enabled) {
            public Input { if (cron == null) throw new IllegalArgumentException("cron is required"); }
        }
    }

    public static class CronDeleteTool extends BaseTool<CronDeleteTool.Input> {
        public CronDeleteTool() {
            super("cron_delete", "Cancel a scheduled cron job.", Input.class);
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            Path registryPath = ctx.cwd().resolve(".openharness").resolve("cron_jobs.json");
            List<CronJob> jobs = load(registryPath);
            boolean removed = jobs.removeIf(j -> j.id().equals(args.jobId()));
            if (!removed) return ToolResult.error("Job not found: " + args.jobId());
            save(registryPath, jobs);
            return ToolResult.success("Cron job deleted: " + args.jobId());
        }

        @Override public boolean isReadOnly(Input args) { return false; }

        public record Input(String jobId) {
            public Input { if (jobId == null) throw new IllegalArgumentException("jobId is required"); }
        }
    }

    public static class CronListTool extends BaseTool<Void> {
        public CronListTool() { super("cron_list", "List all scheduled cron jobs.", Void.class); }

        @Override
        public ToolResult execute(Void args, ToolExecutionContext ctx) {
            Path registryPath = ctx.cwd().resolve(".openharness").resolve("cron_jobs.json");
            List<CronJob> jobs = load(registryPath);
            if (jobs.isEmpty()) return ToolResult.success("No cron jobs scheduled.");

            StringBuilder sb = new StringBuilder("Scheduled cron jobs:\n\n");
            for (CronJob j : jobs) {
                String status = j.enabled() ? "active" : "disabled";
                sb.append("- [").append(status).append("] ").append(j.id().substring(0, 8))
                        .append(" ").append(j.cron()).append(" — ").append(j.description()).append("\n");
            }
            return ToolResult.success(sb.toString());
        }

        @Override public boolean isReadOnly(Void args) { return true; }
    }

    public static class CronToggleTool extends BaseTool<CronToggleTool.Input> {
        public CronToggleTool() {
            super("cron_toggle", "Enable or disable a scheduled cron job by ID.", Input.class);
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            Path registryPath = ctx.cwd().resolve(".openharness").resolve("cron_jobs.json");
            List<CronJob> jobs = load(registryPath);
            boolean found = false;
            List<CronJob> updated = new ArrayList<>();
            for (CronJob j : jobs) {
                if (j.id().equals(args.jobId())) {
                    found = true;
                    updated.add(new CronJob(j.id(), j.cron(), j.prompt(), args.enabled(),
                            j.timezone(), j.description(), j.recurring(), j.durable(), j.createdAt()));
                } else {
                    updated.add(j);
                }
            }
            if (!found) return ToolResult.error("Job not found: " + args.jobId());
            save(registryPath, updated);
            String state = args.enabled() ? "enabled" : "disabled";
            return ToolResult.success("Cron job " + args.jobId() + " is now " + state + ".");
        }

        public record Input(String jobId, boolean enabled) {
            public Input {
                if (jobId == null) throw new IllegalArgumentException("jobId is required");
            }
        }
    }

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

    public record CronJob(String id, String cron, String prompt, boolean enabled,
                           String timezone, String description, boolean recurring,
                           String durable, Instant createdAt) {}
}
