package com.openharness.extensions.swarm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utilities for spawning teammate processes.
 * Java equivalent of Python swarm/spawn_utils.py.
 */
public final class SpawnUtils {

    private SpawnUtils() {}

    public static final String TEAMMATE_COMMAND_ENV_VAR = "OPENHARNESS_TEAMMATE_COMMAND";

    /**
     * Environment variables forwarded to spawned teammates.
     * Tmux may start a fresh login shell that does NOT inherit the parent
     * process environment, so we forward any of these that are set.
     */
    private static final List<String> TEAMMATE_ENV_VARS = List.of(
            // API provider selection
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_BASE_URL",
            "CLAUDE_CODE_USE_BEDROCK",
            "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY",
            // Config directory override
            "CLAUDE_CONFIG_DIR",
            // Remote / CCR markers
            "CLAUDE_CODE_REMOTE",
            "CLAUDE_CODE_REMOTE_MEMORY_DIR",
            // Upstream proxy settings
            "HTTPS_PROXY",
            "https_proxy",
            "HTTP_PROXY",
            "http_proxy",
            "NO_PROXY",
            "no_proxy",
            // CA bundle overrides
            "SSL_CERT_FILE",
            "NODE_EXTRA_CA_CERTS",
            "REQUESTS_CA_BUNDLE",
            "CURL_CA_BUNDLE",
            // OpenHarness-native provider settings
            "OPENHARNESS_CONFIG_DIR",
            "OPENHARNESS_DATA_DIR",
            "OPENHARNESS_LOGS_DIR",
            "OPENHARNESS_PROFILE",
            "OPENHARNESS_API_FORMAT",
            "OPENHARNESS_PROVIDER",
            "OPENHARNESS_BASE_URL",
            "OPENHARNESS_MODEL",
            "OPENHARNESS_ANTHROPIC_API_KEY",
            "OPENHARNESS_OPENAI_API_KEY",
            "OPENHARNESS_DASHSCOPE_API_KEY",
            "OPENHARNESS_MOONSHOT_API_KEY",
            "OPENHARNESS_GEMINI_API_KEY",
            "OPENHARNESS_MINIMAX_API_KEY",
            "OPENHARNESS_NVIDIA_API_KEY",
            "OPENHARNESS_MODELSCOPE_API_KEY",
            "OPENAI_API_KEY"
    );

    /**
     * Builds the environment variables for a spawned agent process.
     * Java equivalent of Python build_inherited_env_vars().
     */
    public static Map<String, String> buildInheritedEnvVars() {
        Map<String, String> env = new HashMap<>();
        env.put("OPENHARNESS_AGENT_TEAMS", "1");
        // Spawned workers should behave like workers, not recursively re-enter
        // coordinator mode just because the parent leader had the flag set.
        env.put("CLAUDE_CODE_COORDINATOR_MODE", "0");

        for (String key : TEAMMATE_ENV_VARS) {
            String value = System.getenv(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        return env;
    }

    /**
     * Builds the full environment for a spawned agent process.
     */
    public static Map<String, String> buildEnv(TeammateSpec spec) {
        Map<String, String> env = new HashMap<>(buildInheritedEnvVars());

        if (spec.name() != null) {
            env.put("OPENHARNESS_TEAMMATE_NAME", spec.name());
        }
        if (spec.team() != null) {
            env.put("OPENHARNESS_TEAM_NAME", spec.team());
        }
        if (spec.sessionId() != null) {
            env.put("OPENHARNESS_TEAMMATE_ID", spec.sessionId());
        }
        if (spec.leaderMailboxPath() != null) {
            env.put("OPENHARNESS_LEADER_MAILBOX",
                    spec.leaderMailboxPath().toAbsolutePath().toString());
        }
        if (spec.name() != null) {
            env.put("OPENHARNESS_AGENT_TYPE", spec.name());
        }
        if (spec.model() != null) {
            env.put("OPENHARNESS_MODEL", spec.model());
        }
        if (spec.cwd() != null) {
            env.put("OPENHARNESS_CWD", spec.cwd());
        }
        if (spec.systemPrompt() != null) {
            env.put("OPENHARNESS_SYSTEM_PROMPT", spec.systemPrompt());
        }

        // Merge any additional env from the spec
        spec.env().forEach(env::put);

        return env;
    }

    /**
     * Returns the executable used to spawn teammate processes.
     * Java equivalent of Python get_teammate_command().
     */
    public static String getTeammateCommand() {
        String override = System.getenv(TEAMMATE_COMMAND_ENV_VAR);
        if (override != null && !override.isEmpty()) {
            return override;
        }

        // Prefer the current Java process
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            String javaBin = javaHome + "/bin/java";
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                javaBin += ".exe";
            }
            return javaBin;
        }

        return "java";
    }

    /**
     * Build CLI flags to propagate from the current session to spawned teammates.
     * Java equivalent of Python build_inherited_cli_flags().
     */
    public static List<String> buildInheritedCliFlags(
            String model,
            String systemPrompt,
            String systemPromptMode,
            String permissionMode,
            boolean planModeRequired,
            String settingsPath,
            String teammateMode,
            List<String> pluginDirs,
            List<String> extraFlags) {

        List<String> flags = new ArrayList<>();

        // Permission mode
        if (!planModeRequired) {
            if ("bypassPermissions".equals(permissionMode)) {
                flags.add("--dangerously-skip-permissions");
            } else if ("acceptEdits".equals(permissionMode)) {
                flags.add("--permission-mode");
                flags.add("acceptEdits");
            }
        }

        // Model override
        if (model != null && !model.isEmpty() && !"inherit".equals(model)) {
            flags.add("--model");
            flags.add(shellQuote(model));
        }

        // System prompt override
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            String promptFlag = "append".equals(systemPromptMode)
                    ? "--append-system-prompt" : "--system-prompt";
            flags.add(promptFlag);
            flags.add(shellQuote(systemPrompt));
        }

        // Settings path propagation
        if (settingsPath != null && !settingsPath.isEmpty()) {
            flags.add("--settings");
            flags.add(shellQuote(settingsPath));
        }

        // Plugin directories
        if (pluginDirs != null) {
            for (String pluginDir : pluginDirs) {
                flags.add("--plugin-dir");
                flags.add(shellQuote(pluginDir));
            }
        }

        // Teammate mode propagation
        if (teammateMode != null && !teammateMode.isEmpty()) {
            flags.add("--teammate-mode");
            flags.add(shellQuote(teammateMode));
        }

        if (extraFlags != null) {
            flags.addAll(extraFlags);
        }

        return flags;
    }

    /**
     * Simple shell quoting: wraps the string in single quotes and escapes embedded quotes.
     * Java equivalent of Python shlex.quote().
     */
    public static String shellQuote(String s) {
        if (s.isEmpty()) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Returns true if the tmux binary is on PATH.
     * Java equivalent of Python is_tmux_available().
     */
    public static boolean isTmuxAvailable() {
        return findOnPath("tmux") != null;
    }

    /**
     * Returns true if the current process is running inside a tmux session.
     * Java equivalent of Python is_inside_tmux().
     */
    public static boolean isInsideTmux() {
        return System.getenv("TMUX") != null;
    }

    private static String findOnPath(String name) {
        for (String dir : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            java.io.File file = new java.io.File(dir, name);
            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }
}
