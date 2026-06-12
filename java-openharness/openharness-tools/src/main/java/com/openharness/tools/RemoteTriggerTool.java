package com.openharness.tools;

import com.openharness.common.CronJobRegistry;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Trigger a remote/cron job by name.
 */
public class RemoteTriggerTool extends BaseTool<RemoteTriggerTool.Input> {

    private final CronJobRegistry cronRegistry;

    public RemoteTriggerTool(CronJobRegistry cronRegistry) {
        super("remote_trigger", "Trigger a named cron job by looking it up in the registry and executing its command.", Input.class);
        this.cronRegistry = cronRegistry;
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        var job = cronRegistry.getJob(arguments.name());
        if (job == null) {
            return ToolResult.error("Cron job not found: " + arguments.name());
        }

        String command = job.command();
        if (command == null || command.isBlank()) {
            return ToolResult.error("Cron job has no command: " + arguments.name());
        }

        Path cwd = context.cwd();
        int timeout = arguments.timeoutSeconds();
        if (timeout <= 0 || timeout > 600) timeout = 120;

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Remote trigger timed out after " + timeout + " seconds");
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.exitValue();
            String stdout = output.toString().stripTrailing();
            if (exitCode != 0) {
                return ToolResult.error("Exit code " + exitCode + "\n" + stdout);
            }
            return ToolResult.success("Triggered '" + arguments.name() + "' (exit " + exitCode + "):\n" + stdout);
        } catch (Exception e) {
            return ToolResult.error("Remote trigger failed: " + e.getMessage());
        }
    }

    public record Input(String name, int timeoutSeconds) {
        public Input {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name is required");
            }
        }
    }
}
