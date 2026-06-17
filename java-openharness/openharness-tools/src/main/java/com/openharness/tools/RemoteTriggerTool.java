package com.openharness.tools;

import com.openharness.common.CronJobRegistry;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Trigger a remote/cron job by name.
 */
public class RemoteTriggerTool extends BaseTool<RemoteTriggerTool.Input> {

    private final CronJobRegistry cronRegistry;

    public RemoteTriggerTool(CronJobRegistry cronRegistry) {
        super("remote_trigger", "Trigger a configured local cron-style job immediately.", Input.class);
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

        // Use job's configured cwd if available, fall back to context.cwd()
        Path cwd;
        String jobCwd = job.cwd();
        if (jobCwd != null && !jobCwd.isBlank()) {
            cwd = Path.of(jobCwd.replaceFirst("^~", System.getProperty("user.home")));
        } else {
            cwd = context.cwd();
        }

        int timeout = arguments.timeoutSeconds();
        if (timeout <= 0 || timeout > 600) timeout = 120;

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(cwd.toFile());
            Process process = pb.start();

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return ToolResult.error("Remote trigger timed out after " + timeout + " seconds");
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            int exitCode = process.exitValue();

            // Build output: "Triggered {name}\n{stdout}" with stderr appended separately
            String stdoutClean = stdout.replace("\r\n", "\n").stripTrailing();
            String stderrClean = stderr.replace("\r\n", "\n").stripTrailing();

            StringBuilder sb = new StringBuilder();
            sb.append("Triggered ").append(arguments.name());
            boolean hasOutput = false;

            if (!stdoutClean.isEmpty()) {
                sb.append("\n").append(stdoutClean);
                hasOutput = true;
            }
            if (!stderrClean.isEmpty()) {
                sb.append("\n(stderr) ").append(stderrClean);
                hasOutput = true;
            }
            if (!hasOutput) {
                sb.append("\n(no output)");
            }

            return new ToolResult(sb.toString(), exitCode != 0,
                    Map.of("returncode", exitCode));

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
