package com.openharness.extensions.sandbox;

import com.openharness.config.SandboxSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts bash command execution and wraps it through the SRT sandbox
 * when sandbox mode is enabled.
 *
 * Java equivalent of Python sandbox/bash_interceptor.py.
 */
public class BashSandboxInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(BashSandboxInterceptor.class);

    private final SandboxManager sandboxManager;
    private final boolean enabled;
    private final Map<String, String> toolSandboxMap = new ConcurrentHashMap<>();

    // Tools that should always be sandboxed when sandbox is enabled
    private static final List<String> SANDBOXABLE_TOOLS = List.of("bash", "execute");

    // Commands that should never be sandboxed (meta-commands)
    private static final List<String> SKIP_COMMANDS = List.of(
            "true", "false", "echo", "pwd", "cd", "export ", "unset ",
            "which ", "type ", "command -v", "hash ");

    public BashSandboxInterceptor(SandboxManager sandboxManager) {
        this.sandboxManager = sandboxManager;
        this.enabled = sandboxManager.isAvailable();
        logger.info("BashSandboxInterceptor: sandbox enabled={}, backend={}",
                enabled, sandboxManager.activeBackend());
    }

    public BashSandboxInterceptor() {
        this(new SandboxManager());
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    public void setToolSandboxed(String toolName, boolean sandboxed) {
        if (sandboxed) {
            toolSandboxMap.put(toolName, "enabled");
        } else {
            toolSandboxMap.remove(toolName);
        }
    }

    public boolean isToolSandboxed(String toolName) {
        return toolSandboxMap.containsKey(toolName)
                || SANDBOXABLE_TOOLS.contains(toolName);
    }

    // ------------------------------------------------------------------
    // Command interception
    // ------------------------------------------------------------------

    /**
     * Check if a command should be sandboxed and wrap it if needed.
     *
     * @param command     the original bash command
     * @param toolName    the tool making the call
     * @param workingDir  current working directory
     * @return wrapped command if sandbox applies, or original command if skipped
     */
    public String interceptCommand(String command, String toolName, Path workingDir) {
        if (!enabled) return command;
        if (!isToolSandboxed(toolName)) return command;
        if (shouldSkipSandbox(command)) return command;

        logger.debug("Sandbox intercept: {} via {}", command, toolName);
        return buildSandboxedCommand(command, workingDir);
    }

    /**
     * Execute a command via the sandbox and return the result.
     * Falls back to unsandboxed execution if sandbox is unavailable.
     */
    public SandboxManager.SandboxExecutionResult executeSandboxed(
            String command, String toolName, Path workingDir) {

        if (!enabled || !isToolSandboxed(toolName) || shouldSkipSandbox(command)) {
            return new SandboxManager.SandboxExecutionResult(false, false,
                    null, "Sandbox skipped");
        }

        return sandboxManager.execute(command, toolName, workingDir);
    }

    // ------------------------------------------------------------------
    // Command wrapping
    // ------------------------------------------------------------------

    /**
     * Build the full sandboxed command string that replaces the original.
     */
    private String buildSandboxedCommand(String command, Path workingDir) {
        try {
            SandboxSettings settings = sandboxManager.settingsFor("bash");
            Path configFile = sandboxManager.srt().writeConfigFile(settings);
            List<String> wrapped = sandboxManager.srt().wrapCommand(command, configFile, false);

            // Return as a single shell-safe string
            StringBuilder sb = new StringBuilder();
            for (String part : wrapped) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(shellEscape(part));
            }
            // Schedule config cleanup after the command runs
            sb.append("; rm -f ").append(shellEscape(configFile.toString()));
            return sb.toString();
        } catch (IOException e) {
            logger.warn("Failed to build sandboxed command, running unsandboxed: {}", e.getMessage());
            return command;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean shouldSkipSandbox(String command) {
        if (command == null || command.isBlank()) return true;
        String trimmed = command.trim();
        for (String skip : SKIP_COMMANDS) {
            if (trimmed.startsWith(skip)) return true;
        }
        return false;
    }

    private static String shellEscape(String s) {
        if (s.isEmpty()) return "''";
        // Single-quote escaping: replace ' with '\''
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------
    // SandboxManager access
    // ------------------------------------------------------------------

    public SandboxManager sandboxManager() {
        return sandboxManager;
    }

    public Map<String, Object> status() {
        return Map.of(
                "enabled", enabled,
                "backend", sandboxManager.activeBackend(),
                "engine", sandboxManager.sandboxEngine(),
                "sandboxed_tools", List.copyOf(toolSandboxMap.keySet()),
                "always_sandboxed", SANDBOXABLE_TOOLS
        );
    }
}
