package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Shell command execution tool with optional SRT sandbox support.
 * Java equivalent of Python's BashTool.
 */
public class BashTool extends BaseTool<BashTool.Input> {

    private static final Logger LOG = Logger.getLogger(BashTool.class.getName());
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int READ_REMAINING_TIMEOUT = 2;

    /** Sandbox interceptor — set via {@link #setSandboxInterceptor}. */
    private static volatile com.openharness.extensions.sandbox.BashSandboxInterceptor sandboxInterceptor;

    /**
     * Enable sandbox interception for bash command execution.
     * Called by GatewayEngineFactory during engine wiring.
     */
    public static void setSandboxInterceptor(
            com.openharness.extensions.sandbox.BashSandboxInterceptor interceptor) {
        sandboxInterceptor = interceptor;
    }

    public BashTool() {
        super("bash", "Run a shell command in the local repository.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path cwd = arguments.cwd() != null
                ? Path.of(arguments.cwd().replaceFirst("^~", System.getProperty("user.home")))
                : context.cwd();

        // Intercept command through sandbox if enabled
        String effectiveCommand = arguments.command();
        if (sandboxInterceptor != null && sandboxInterceptor.isEnabled()) {
            effectiveCommand = sandboxInterceptor.interceptCommand(
                    effectiveCommand, "bash", cwd);
        }

        // Preflight: reject interactive commands
        String preflightError = preflightInteractive(arguments.command());
        if (preflightError != null) {
            return ToolResult.error(preflightError);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", effectiveCommand);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int timeout = arguments.timeoutSeconds() > 0 && arguments.timeoutSeconds() <= DEFAULT_TIMEOUT_SECONDS
                    ? arguments.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Command timed out after " + timeout + " seconds");
            }

            // Read remaining output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.exitValue();
            String stdout = output.toString().stripTrailing();
            if (!stdout.isEmpty()) stdout += "\n";

            if (exitCode != 0) {
                return new ToolResult("Exit code " + exitCode + "\n" + stdout, true);
            }

            return ToolResult.success(stdout.isEmpty() ? "(no output)" : stdout);

        } catch (Exception e) {
            return ToolResult.error("Failed to execute command: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false; // bash is always mutating at the permission level
    }

    /**
     * Reject commands that require interactive input.
     */
    static String preflightInteractive(String command) {
        String trimmed = command.stripLeading();
        String lower = trimmed.toLowerCase();

        String[] interactiveCommands = {
                "ssh ", "vim ", "vi ", "nano ", "emacs ", "less ", "more ",
                "top ", "htop ", "sudo ", "su -", "su ", "passwd",
                "mysql ", "psql", "redis-cli", "mongo",
                "python ", "python3 ", "node ", "irb ", "rails console"
        };

        for (String ic : interactiveCommands) {
            if (lower.startsWith(ic) || lower.contains("| " + ic) || lower.contains("&& " + ic)) {
                return "Interactive command '" + ic.trim()
                        + "' requires a TTY and cannot run in non-interactive mode.";
            }
        }

        if (lower.startsWith("sudo ") || lower.contains("| sudo ") || lower.contains("&& sudo ")) {
            return "sudo requires interactive password input; it can only run when the "
                    + "NOPASSWD directive is configured for the current user.";
        }

        return null;
    }

    public record Input(String command, String cwd, int timeoutSeconds) {
        public Input {
            if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
    }
}
