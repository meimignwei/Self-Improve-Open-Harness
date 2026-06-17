package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
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
    private static final int MAX_OUTPUT_CHARS = 12000;

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

        // Preflight: reject interactive commands (merged Python + Java lists)
        String preflightError = preflightInteractive(arguments.command());
        if (preflightError != null) {
            return new ToolResult(preflightError, true,
                    Map.of("interactive_required", true));
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", effectiveCommand);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            // Close stdin so the process does not block waiting for input
            process.getOutputStream().close();

            int timeout = arguments.timeoutSeconds() > 0 && arguments.timeoutSeconds() <= DEFAULT_TIMEOUT_SECONDS
                    ? arguments.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                // Timeout: destroy process then collect any partial output
                process.destroyForcibly();
                process.waitFor(READ_REMAINING_TIMEOUT, TimeUnit.SECONDS);

                String output;
                try {
                    output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    output = "";
                }

                int exitCode = process.exitValue();
                String text = formatOutput(output);
                String timeoutMsg = formatTimeoutOutput(text, arguments.command(), timeout);

                return new ToolResult(timeoutMsg, true,
                        Map.of("returncode", exitCode, "timed_out", true));
            }

            // Process finished normally — read all output
            String output;
            try {
                output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                output = "";
            }

            int exitCode = process.exitValue();
            String text = formatOutput(output);

            return new ToolResult(text, exitCode != 0,
                    Map.of("returncode", exitCode));

        } catch (Exception e) {
            return ToolResult.error("Failed to execute command: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false; // bash is always mutating at the permission level
    }

    // ------------------------------------------------------------------
    // Output formatting
    // ------------------------------------------------------------------

    /**
     * Format output: decode, strip, handle empty, truncate if too long.
     */
    static String formatOutput(String raw) {
        String text = raw.replace("\r\n", "\n").strip();
        if (text.isEmpty()) {
            return "(no output)";
        }
        if (text.length() > MAX_OUTPUT_CHARS) {
            return text.substring(0, MAX_OUTPUT_CHARS) + "\n...[truncated]...";
        }
        return text;
    }

    /**
     * Format a timeout error message with partial output and hints.
     */
    static String formatTimeoutOutput(String partialText, String command, int timeout) {
        StringBuilder sb = new StringBuilder();
        sb.append("Command timed out after ").append(timeout).append(" seconds.");
        if (!"(no output)".equals(partialText)) {
            sb.append("\n\nPartial output:\n").append(partialText);
        }
        String hint = interactiveCommandHint(command, partialText);
        if (hint != null) {
            sb.append("\n").append(hint);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Interactive detection (merged Python + Java lists)
    // ------------------------------------------------------------------

    /**
     * Pre-flight check merging Python scaffold detection and Java interactive
     * program detection. Returns an error message if the command looks interactive,
     * or null if it looks safe to run non-interactively.
     */
    static String preflightInteractive(String command) {
        String lowered = command.stripLeading().toLowerCase();

        // 1. Python-style scaffold detection (with non-interactive flag exceptions)
        String scaffoldError = checkScaffold(lowered);
        if (scaffoldError != null) {
            return scaffoldError;
        }

        // 2. Java-style interactive program detection
        return checkInteractivePrograms(lowered);
    }

    // ---- Scaffold markers (Python) ----

    static final String[] SCAFFOLD_MARKERS = {
            "create-next-app",
            "npm create ",
            "pnpm create ",
            "yarn create ",
            "bun create ",
            "pnpm dlx ",
            "npm init ",
            "pnpm init ",
            "yarn init ",
            "bunx create-",
            "npx create-",
    };

    static final String[] NON_INTERACTIVE_MARKERS = {
            "--yes", " -y", "--skip-install", "--defaults", "--non-interactive", "--ci",
    };

    static String checkScaffold(String loweredCommand) {
        boolean hasScaffold = false;
        for (String marker : SCAFFOLD_MARKERS) {
            if (loweredCommand.contains(marker)) {
                hasScaffold = true;
                break;
            }
        }
        if (!hasScaffold) {
            return null;
        }
        for (String marker : NON_INTERACTIVE_MARKERS) {
            if (loweredCommand.contains(marker)) {
                return null; // non-interactive flag present — allow
            }
        }
        return "This command appears to require interactive input before it can continue. "
                + "The bash tool is non-interactive, so it cannot answer installer/scaffold prompts live. "
                + "Prefer non-interactive flags (for example --yes, -y, --skip-install, --defaults, --non-interactive), "
                + "or run the scaffolding step once in an external terminal before asking the agent to continue.";
    }

    // ---- Interactive programs (Java) ----

    static final String[] INTERACTIVE_COMMANDS = {
            "ssh ", "vim ", "vi ", "nano ", "emacs ", "less ", "more ",
            "top ", "htop ", "sudo ", "su -", "su ", "passwd",
            "mysql ", "psql", "redis-cli", "mongo",
            "python ", "python3 ", "node ", "irb ", "rails console",
    };

    static String checkInteractivePrograms(String loweredCommand) {
        for (String ic : INTERACTIVE_COMMANDS) {
            if (loweredCommand.startsWith(ic)
                    || loweredCommand.contains("| " + ic)
                    || loweredCommand.contains("&& " + ic)) {
                return "This command appears to require interactive input before it can continue. "
                        + "The bash tool is non-interactive, so it cannot answer interactive prompts live. "
                        + "Prefer non-interactive alternatives or run the command in an external terminal.";
            }
        }

        // sudo-specific message (kept separate for clarity about NOPASSWD)
        if (loweredCommand.startsWith("sudo ")
                || loweredCommand.contains("| sudo ")
                || loweredCommand.contains("&& sudo ")) {
            return "sudo requires interactive password input; it can only run when the "
                    + "NOPASSWD directive is configured for the current user.";
        }

        return null;
    }

    // ------------------------------------------------------------------
    // Timeout hint
    // ------------------------------------------------------------------

    static final String[] PROMPT_MARKERS = {
            "would you like",
            "ok to proceed",
            "select an option",
            "which",
            "press enter to continue",
            "?",
    };

    static String interactiveCommandHint(String command, String output) {
        String loweredCommand = command.toLowerCase();
        if (checkScaffold(loweredCommand) != null || looksLikePrompt(output)) {
            return "This command appears to require interactive input. "
                    + "The bash tool is non-interactive, so prefer non-interactive flags "
                    + "(for example --yes, -y, --skip-install, or similar) or run the "
                    + "scaffolding step once in an external terminal before continuing.";
        }
        return null;
    }

    static boolean looksLikePrompt(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String lowered = output.toLowerCase();
        for (String marker : PROMPT_MARKERS) {
            if (lowered.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    public record Input(String command, String cwd, int timeoutSeconds) {
        public Input {
            if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
    }
}
