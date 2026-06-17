package com.openharness.cli;

import com.openharness.api.AnthropicMessagesClient;
import com.openharness.api.OpenAICompatibleClient;
import com.openharness.api.StreamingApiClient;
import com.openharness.config.AtomicFileWriter;
import com.openharness.config.ProviderProfile;
import com.openharness.config.Settings;
import com.openharness.engine.QueryEngine;
import com.openharness.engine.tool.ToolRegistry;
import com.openharness.extensions.mcp.ConnectionState;
import com.openharness.extensions.mcp.McpClientManager;
import com.openharness.extensions.mcp.McpConnectionState;
import com.openharness.extensions.mcp.McpServerConfig;
import com.openharness.extensions.memory.MemoryTools;
import com.openharness.extensions.plugins.PluginLoader;
import com.openharness.permissions.PermissionChecker;
import com.openharness.tools.McpTool;
import com.openharness.tools.McpToolAdapter;
import com.openharness.tools.ToolBootstrap;
import com.openharness.tools.ToolSearchTool;
import com.openharness.ui.OpenHarnessApp;
import com.openharness.ui.RuntimeFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Root CLI entry point for OpenHarness.
 * Java equivalent of Python's CLI (Typer) entry.
 */
@Command(name = "oh",
        description = "OpenHarness — AI coding assistant CLI framework",
        subcommands = {MainCommand.RunCmd.class, MainCommand.ConfigCmd.class,
                MainCommand.DoctorCmd.class, MainCommand.SetupCmd.class,
                MainCommand.InitCmd.class, MainCommand.GatewayCmd.class,
                MainCommand.McpCmd.class},
        mixinStandardHelpOptions = true,
        versionProvider = MainCommand.VersionProvider.class)
public class MainCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // No subcommand → launch interactive session (like Python `oh` with no args)
        return new RunCmd().call();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MainCommand()).execute(args);
        System.exit(exitCode);
    }

    @Command(name = "mcp", description = "Manage MCP servers",
            subcommands = {MainCommand.McpCmd.ListCmd.class,
                    MainCommand.McpCmd.AddCmd.class,
                    MainCommand.McpCmd.RemoveCmd.class},
            mixinStandardHelpOptions = true)
    static class McpCmd implements Callable<Integer> {
        @Override public Integer call() {
            // Default: list MCP servers
            return new ListCmd().call();
        }

        @Command(name = "list", description = "List configured MCP servers")
        static class ListCmd implements Callable<Integer> {
            @Override public Integer call() {
                var settings = Settings.load();
                var configs = new RunCmd().loadMcpConfigs(settings);
                if (configs.isEmpty()) {
                    System.out.println("No MCP servers configured.");
                    return 0;
                }
                var mcpManager = new McpClientManager();
                mcpManager.connectAll(configs);
                for (var s : mcpManager.listStatuses()) {
                    System.out.printf("[%s] %s (%s) tools:%d resources:%d%n",
                            s.state().name().toLowerCase(), s.name(), s.transport(),
                            s.tools().size(), s.resources().size());
                }
                mcpManager.closeAll();
                return 0;
            }
        }

        @Command(name = "add", description = "Add an MCP server configuration")
        static class AddCmd implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", description = "Server name")
            private String name;
            @CommandLine.Parameters(index = "1", description = "JSON config (e.g. '{\"command\":\"...\",\"transport\":\"stdio\"}')")
            private String jsonConfig;

            @Override public Integer call() {
                if (name == null || name.isBlank() || jsonConfig == null || jsonConfig.isBlank()) {
                    System.err.println("Usage: oh mcp add <name> <json>");
                    return 1;
                }
                try {
                    var mapper = com.openharness.common.OpenHarnessObjectMapper.get();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> serverConfig = mapper.readValue(jsonConfig, Map.class);

                    java.nio.file.Path mcpFile = com.openharness.config.Paths.configDir().resolve("mcp_servers.json");
                    Map<String, Object> root = new LinkedHashMap<>();
                    if (java.nio.file.Files.exists(mcpFile)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> existing = mapper.readValue(mcpFile.toFile(), Map.class);
                        root.putAll(existing);
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> servers = (Map<String, Object>) root.getOrDefault("mcp_servers", new LinkedHashMap<>());
                    servers.put(name, serverConfig);
                    root.put("mcp_servers", servers);

                    com.openharness.config.AtomicFileWriter.writeJson(mcpFile, root);
                    System.out.println("Added MCP server: " + name);
                    return 0;
                } catch (Exception e) {
                    System.err.println("Failed to add MCP server: " + e.getMessage());
                    return 1;
                }
            }
        }

        @Command(name = "remove", description = "Remove an MCP server configuration")
        static class RemoveCmd implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", description = "Server name to remove")
            private String name;

            @Override public Integer call() {
                if (name == null || name.isBlank()) {
                    System.err.println("Usage: oh mcp remove <name>");
                    return 1;
                }
                try {
                    var mapper = com.openharness.common.OpenHarnessObjectMapper.get();
                    java.nio.file.Path mcpFile = com.openharness.config.Paths.configDir().resolve("mcp_servers.json");
                    if (!java.nio.file.Files.exists(mcpFile)) {
                        System.out.println("No MCP config file found.");
                        return 0;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> root = mapper.readValue(mcpFile.toFile(), Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> servers = (Map<String, Object>) root.getOrDefault("mcp_servers", Map.of());
                    if (servers.remove(name) != null) {
                        root.put("mcp_servers", servers);
                        com.openharness.config.AtomicFileWriter.writeJson(mcpFile, root);
                        System.out.println("Removed MCP server: " + name);
                    } else {
                        System.out.println("MCP server '" + name + "' not found.");
                    }
                    return 0;
                } catch (Exception e) {
                    System.err.println("Failed to remove MCP server: " + e.getMessage());
                    return 1;
                }
            }
        }
    }

    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"OpenHarness Java v0.1.0", "Java 21+ / Picocli 4.7 / JLine 3"};
        }
    }

    @Command(name = "run", description = "Start an interactive assistant session")
    static class RunCmd implements Callable<Integer> {
        @Option(names = {"-p", "--prompt"}, description = "Initial prompt (non-interactive)")
        private String prompt;

        @Option(names = {"-m", "--model"}, description = "Model override")
        private String model;

        @Option(names = {"-o", "--output"}, description = "Output style: print|tui|backend")
        private String outputStyle;

        @Override
        public Integer call() {
            var settings = Settings.load();
            if (model != null) settings.setModel(model);
            if (outputStyle != null) settings.setOutputStyle(outputStyle);

            var mode = RuntimeFactory.resolveMode(settings.outputStyle());

            // 1. Create API client
            StreamingApiClient apiClient = createApiClient(settings);

            // 2. Create tool registry with basic tools
            ToolRegistry registry = ToolBootstrap.createBasicRegistry();

            // 3. Register MemoryTools
            registry.register(new MemoryTools.MemoryCreateTool());
            registry.register(new MemoryTools.MemoryReadTool());
            registry.register(new MemoryTools.MemorySearchTool());
            registry.register(new MemoryTools.MemoryDeleteTool());

            // 4. Register MCP tools
            McpClientManager mcpManager = new McpClientManager();
            List<McpServerConfig> mcpConfigs = loadMcpConfigs(settings);
            if (!mcpConfigs.isEmpty()) {
                mcpManager.connectAll(mcpConfigs);
            }
            registry.register(new McpTool(mcpManager));
            registry.register(new com.openharness.tools.McpTools.ListMcpResourcesTool(mcpManager));
            registry.register(new com.openharness.tools.McpTools.ReadMcpResourceTool(mcpManager));
            registry.register(new com.openharness.tools.McpTools.ListMcpPromptsTool(mcpManager));
            registry.register(new com.openharness.tools.McpTools.GetMcpPromptTool(mcpManager));
            for (var mcpToolInfo : mcpManager.listTools()) {
                registry.register(new McpToolAdapter(mcpManager, mcpToolInfo));
            }

            // 5. Register tool search (needs registry itself)
            registry.register(new ToolSearchTool(registry));

            // 6. Create permission checker and query engine with confirmation callback
            var permissionChecker = new PermissionChecker(settings.permission());
            var confirmCallback = createConfirmCallback(mode);

            // Tool carryover for persistent context across turns
            java.nio.file.Path carryoverPath = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".openharness", "carryover.json");
            var carryover = new com.openharness.engine.ToolCarryover(carryoverPath);

            // Auto-compaction with threshold from settings
            int compactThreshold = settings.autoCompactThresholdTokens() != null
                    ? settings.autoCompactThresholdTokens() : 8000;
            var autoCompact = new com.openharness.engine.AutoCompactState(compactThreshold);

            var costTracker = new com.openharness.engine.CostTracker();
            var queryEngine = new QueryEngine(apiClient, registry, permissionChecker,
                    costTracker, autoCompact, carryover, confirmCallback);

            // 7. Run app
            var app = new OpenHarnessApp(settings, mode, queryEngine);
            app.setMcpManager(mcpManager);
            try {
                app.run(prompt);
            } finally {
                mcpManager.closeAll();
            }
            return 0;
        }

        private java.util.function.BiFunction<String, String, Boolean> createConfirmCallback(
                com.openharness.ui.RuntimeOutput.Mode mode) {
            return switch (mode) {
                case TUI -> {
                    var dialog = new com.openharness.ui.PermissionDialog();
                    yield (toolName, reason) -> {
                        var resp = dialog.ask(toolName, reason);
                        return resp == com.openharness.ui.PermissionDialog.Response.ALLOW
                                || resp == com.openharness.ui.PermissionDialog.Response.ALLOW_ALL;
                    };
                }
                case PRINT, BACKEND -> {
                    var scanner = new java.util.Scanner(System.in);
                    yield (toolName, reason) -> {
                        System.out.println();
                        System.out.println("┌─ Permission Check ─────────────────────");
                        System.out.println("│ Tool: " + toolName);
                        System.out.println("│ " + reason);
                        System.out.println("│ [y] Allow once  [n] Deny");
                        System.out.print("└─> ");
                        System.out.flush();
                        try {
                            String line = scanner.nextLine().trim().toLowerCase();
                            return line.equals("y") || line.equals("yes");
                        } catch (Exception e) {
                            return false;
                        }
                    };
                }
            };
        }

        private StreamingApiClient createApiClient(Settings settings) {
            String apiKey = resolveApiKey(settings);
            String provider = settings.provider();
            if (provider == null || provider.isBlank()) {
                provider = settings.activeProfile();
            }

            ProviderProfile profile = settings.mergedProfiles().get(provider);
            String apiFormat = profile != null ? profile.apiFormat() : settings.apiFormat();
            String baseUrl = profile != null && profile.baseUrl() != null
                    ? profile.baseUrl()
                    : settings.baseUrl();

            if ("anthropic".equals(apiFormat)) {
                return new AnthropicMessagesClient(apiKey, baseUrl, null);
            }
            return new OpenAICompatibleClient(apiKey, baseUrl);
        }

        private String resolveApiKey(Settings settings) {
            String key = settings.apiKey();
            if (key != null && !key.isBlank()) {
                return key;
            }
            String env = System.getenv("ANTHROPIC_API_KEY");
            if (env != null && !env.isBlank()) {
                return env;
            }
            env = System.getenv("OPENAI_API_KEY");
            if (env != null && !env.isBlank()) {
                return env;
            }
            return "";
        }

        @SuppressWarnings("unchecked")
        List<McpServerConfig> loadMcpConfigs(Settings settings) {
            List<McpServerConfig> configs = new ArrayList<>();
            Map<String, McpServerConfig> mergedMcpServers = new LinkedHashMap<>();

            // 1. Load from settings (~/.openharness/mcp_servers.json)
            java.nio.file.Path configDir = com.openharness.config.Paths.configDir();
            java.nio.file.Path mcpFile = configDir.resolve("mcp_servers.json");
            if (java.nio.file.Files.exists(mcpFile)) {
                try {
                    var mapper = com.openharness.common.OpenHarnessObjectMapper.get();
                    Map<String, Object> root = mapper.readValue(mcpFile.toFile(), Map.class);
                    Map<String, Object> servers = (Map<String, Object>) root.getOrDefault("mcp_servers", Map.of());
                    for (Map.Entry<String, Object> entry : servers.entrySet()) {
                        McpServerConfig parsed = parseMcpServerConfig(entry.getKey(), entry.getValue());
                        if (parsed != null) {
                            mergedMcpServers.put(entry.getKey(), parsed);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Warning: failed to load MCP configs: " + e.getMessage());
                }
            }

            // 2. Load from plugins and merge (plugin: prefix for conflicts)
            try {
                PluginLoader pluginLoader = new PluginLoader();
                java.nio.file.Path cwd = java.nio.file.Path.of(".").toAbsolutePath().normalize();
                var plugins = pluginLoader.loadAll(cwd, Map.of());
                for (var plugin : plugins) {
                    try {
                        Map<String, McpServerConfig> pluginMcp = pluginLoader.loadPluginMcp(plugin);
                        for (var entry : pluginMcp.entrySet()) {
                            String serverName = entry.getKey();
                            // If name conflicts with a settings server, namespace it
                            if (mergedMcpServers.containsKey(serverName)) {
                                serverName = plugin.name() + ":" + serverName;
                            }
                            mergedMcpServers.putIfAbsent(serverName, entry.getValue());
                        }
                    } catch (Exception e) {
                        // skip plugins that fail to load MCP
                    }
                }
            } catch (Exception e) {
                // plugin loading is best-effort
            }

            return List.copyOf(mergedMcpServers.values());
        }

        @SuppressWarnings("unchecked")
        private McpServerConfig parseMcpServerConfig(String name, Object rawConfig) {
            Map<String, Object> server = (Map<String, Object>) rawConfig;
            String transport = (String) server.getOrDefault("transport", "stdio");
            Map<String, Object> auth = loadMcpAuth(server);
            try {
                return switch (transport) {
                    case "stdio" -> {
                        String command = (String) server.get("command");
                        List<String> args = (List<String>) server.getOrDefault("args", List.of());
                        Map<String, String> env = (Map<String, String>) server.getOrDefault("env", Map.of());
                        String cwd = (String) server.get("cwd");
                        if (command != null && !command.isBlank()) {
                            yield new McpServerConfig.StdioConfig(name, command, args, env, cwd);
                        }
                        yield null;
                    }
                    case "http", "sse" -> {
                        String url = (String) server.get("url");
                        Map<String, String> headers = (Map<String, String>) server.getOrDefault("headers", Map.of());
                        if (url != null && !url.isBlank()) {
                            yield new McpServerConfig.HttpConfig(name, url, headers, auth);
                        }
                        yield null;
                    }
                    case "ws", "websocket" -> {
                        String url = (String) server.get("url");
                        Map<String, String> headers = (Map<String, String>) server.getOrDefault("headers", Map.of());
                        if (url != null && !url.isBlank()) {
                            yield new McpServerConfig.WebSocketConfig(name, url, headers, auth);
                        }
                        yield null;
                    }
                    case "streamable-http", "streamable_http" -> {
                        String url = (String) server.get("url");
                        Map<String, String> headers = (Map<String, String>) server.getOrDefault("headers", Map.of());
                        if (url != null && !url.isBlank()) {
                            yield new McpServerConfig.StreamableHttpConfig(name, url, headers, auth);
                        }
                        yield null;
                    }
                    default -> null;
                };
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> loadMcpAuth(Map<String, Object> serverConfig) {
            Map<String, Object> auth = (Map<String, Object>) serverConfig.get("auth");
            if (auth == null) {
                // Fallback: check for top-level bearer_token or api_key
                String bearerToken = (String) serverConfig.get("bearer_token");
                if (bearerToken != null && !bearerToken.isBlank()) {
                    return Map.of("type", "bearer", "token", bearerToken);
                }
                String apiKey = (String) serverConfig.get("api_key");
                if (apiKey != null && !apiKey.isBlank()) {
                    return Map.of("type", "header", "key", "Authorization", "value", "Bearer " + apiKey);
                }
                return Map.of();
            }
            return auth;
        }
    }

    @Command(name = "config", description = "View or modify configuration")
    static class ConfigCmd implements Callable<Integer> {
        @Option(names = {"show", "--show"}, defaultValue = "true",
                description = "Show current settings")
        private boolean show;

        @Option(names = {"--set"}, description = "Set a config key=value pair")
        private String set;

        @Override
        public Integer call() {
            var settings = Settings.load();

            if (set != null) {
                String[] parts = set.split("=", 2);
                if (parts.length == 2) {
                    applySetting(settings, parts[0].trim(), parts[1].trim());
                    settings.save();
                    System.out.println("Saved: " + parts[0] + " = " + parts[1]);
                } else {
                    System.err.println("Invalid format. Use key=value");
                }
            }

            if (show) {
                System.out.println("Active configuration:");
                System.out.println("  model: " + settings.model());
                System.out.println("  provider: " + settings.provider());
                System.out.println("  theme: " + settings.theme());
                System.out.println("  outputStyle: " + settings.outputStyle());
                System.out.println("  permission: " + settings.permission().mode());
                System.out.println("  vimMode: " + settings.vimMode());
                System.out.println("  voiceMode: " + settings.voiceMode());
            }
            return 0;
        }

        private void applySetting(Settings s, String key, String value) {
            switch (key) {
                case "model" -> s.setModel(value);
                case "provider" -> s.setProvider(value);
                case "theme" -> s.setTheme(value);
                case "outputStyle" -> s.setOutputStyle(value);
                case "vimMode" -> s.setVimMode(Boolean.parseBoolean(value));
                case "voiceMode" -> s.setVoiceMode(Boolean.parseBoolean(value));
                default -> System.err.println("Unknown key: " + key);
            }
        }
    }

    @Command(name = "doctor", description = "Diagnose environment and configuration")
    static class DoctorCmd implements Callable<Integer> {

        @Override
        public Integer call() {
            System.out.println("OpenHarness Doctor Report");
            System.out.println("=========================");
            System.out.println();

            System.out.println("--- OS ---");
            System.out.println("  os.name: " + System.getProperty("os.name"));
            System.out.println("  os.version: " + System.getProperty("os.version"));
            System.out.println("  os.arch: " + System.getProperty("os.arch"));
            System.out.println();

            System.out.println("--- Java ---");
            System.out.println("  java.version: " + System.getProperty("java.version"));
            System.out.println("  java.home: " + System.getProperty("java.home"));
            System.out.println("  virtual threads: " + (Runtime.version().feature() >= 21 ? "available" : "unavailable"));
            System.out.println();

            System.out.println("--- OpenHarness ---");
            var settings = Settings.load();
            System.out.println("  config dir: " + com.openharness.config.Paths.configDir());
            System.out.println("  config file: " + com.openharness.config.Paths.configFilePath());
            System.out.println("  config exists: " + java.nio.file.Files.exists(com.openharness.config.Paths.configFilePath()));
            System.out.println("  model: " + settings.model());
            System.out.println("  provider: " + settings.provider());
            System.out.println();

            System.out.println("--- Environment ---");
            String[] envVars = {"JAVA_HOME", "OPENHARNESS_CONFIG_DIR", "ANTHROPIC_API_KEY",
                    "OPENAI_API_KEY", "SLACK_BOT_TOKEN", "FEISHU_APP_ID"};
            for (String env : envVars) {
                String val = System.getenv(env);
                System.out.println("  " + env + ": " + (val != null ? "***set***" : "(not set)"));
            }

            return 0;
        }
    }

    @Command(name = "setup", description = "Interactive setup wizard — pick provider and authenticate")
    static class SetupCmd implements Callable<Integer> {

        @Override
        public Integer call() {
            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════╗");
            System.out.println("  ║     OpenHarness Setup Wizard            ║");
            System.out.println("  ╚══════════════════════════════════════════╝");
            System.out.println();

            var settings = Settings.load();
            var scanner = new java.util.Scanner(System.in);

            // Step 1: Pick provider
            System.out.println("  Select your AI provider:");
            System.out.println("    1. Anthropic (Claude)     — recommended");
            System.out.println("    2. OpenAI (GPT)");
            System.out.println("    3. DeepSeek");
            System.out.println("    4. DashScope (Qwen)");
            System.out.println("    5. Moonshot (Kimi)");
            System.out.println("    6. Gemini");
            System.out.print("  Choice [1]: ");
            System.out.flush();

            String choice = scanner.nextLine().trim();
            if (choice.isEmpty()) choice = "1";

            String provider;
            String envKey;
            String defaultModel;
            String baseUrl = null;
            switch (choice) {
                case "1" -> {
                    provider = "codex";
                    envKey = "ANTHROPIC_API_KEY";
                    defaultModel = "claude-sonnet-4-6";
                }
                case "2" -> {
                    provider = "openai";
                    envKey = "OPENAI_API_KEY";
                    defaultModel = "gpt-4o";
                }
                case "3" -> {
                    provider = "deepseek";
                    envKey = "DEEPSEEK_API_KEY";
                    defaultModel = "deepseek-chat";
                }
                case "4" -> {
                    provider = "dashscope";
                    envKey = "DASHSCOPE_API_KEY";
                    defaultModel = "qwen-max";
                }
                case "5" -> {
                    provider = "moonshot";
                    envKey = "MOONSHOT_API_KEY";
                    defaultModel = "moonshot-v1-8k";
                }
                case "6" -> {
                    provider = "gemini";
                    envKey = "GEMINI_API_KEY";
                    defaultModel = "gemini-pro";
                }
                default -> {
                    System.err.println("Invalid choice: " + choice);
                    return 1;
                }
            }

            System.out.println();
            System.out.println("  Provider: " + provider);

            // Step 2: API key
            String existingKey = System.getenv(envKey);
            if (existingKey != null && !existingKey.isBlank()) {
                System.out.println("  API key: found in $" + envKey + " (***" +
                        existingKey.substring(Math.max(0, existingKey.length() - 4)) + ")");
                System.out.print("  Use this key? [Y/n]: ");
                System.out.flush();
                String useExisting = scanner.nextLine().trim().toLowerCase();
                if (!useExisting.isEmpty() && !useExisting.startsWith("y")) {
                    System.out.print("  Enter new " + envKey + ": ");
                    System.out.flush();
                    String newKey = scanner.nextLine().trim();
                    if (!newKey.isEmpty()) {
                        System.setProperty(envKey, newKey);
                        existingKey = newKey;
                        System.out.println("  (Key set for this session; add to shell profile for persistence)");
                    }
                }
                // Key already set in environment
            } else {
                System.out.println("  No API key found in environment.");
                System.out.println("  Add this to your shell profile (~/.zshrc or ~/.bashrc):");
                System.out.println("    export " + envKey + "=\"your-key-here\"");
                System.out.println();
                System.out.print("  Or enter API key now (session-only): ");
                System.out.flush();
                String newKey = scanner.nextLine().trim();
                if (!newKey.isEmpty()) {
                    System.setProperty(envKey, newKey);
                    existingKey = newKey;
                }
            }

            // Step 3: Model
            System.out.println();
            System.out.print("  Model [" + defaultModel + "]: ");
            System.out.flush();
            String modelInput = scanner.nextLine().trim();
            String model = modelInput.isEmpty() ? defaultModel : modelInput;
            settings.setModel(model);
            settings.setProvider(provider);

            // Step 4: Permission mode
            System.out.println();
            System.out.println("  Permission mode:");
            System.out.println("    1. default   — prompt for each action");
            System.out.println("    2. plan      — approve plan, then auto-execute");
            System.out.println("    3. full_auto — auto-approve everything");
            System.out.print("  Choice [1]: ");
            System.out.flush();
            String permChoice = scanner.nextLine().trim();
            if (permChoice.isEmpty()) permChoice = "1";
            var permSettings = new com.openharness.config.PermissionSettings();
            switch (permChoice) {
                case "1" -> permSettings.setMode("default");
                case "2" -> permSettings.setMode("plan");
                case "3" -> permSettings.setMode("full_auto");
                default -> {
                    System.err.println("Invalid choice, using default");
                    permSettings.setMode("default");
                }
            }
            settings.setPermission(permSettings);

            // Save
            settings.save();
            System.out.println();
            System.out.println("  Configuration saved!");
            System.out.println("    Config: " + com.openharness.config.Paths.configFilePath());
            System.out.println("    Model:  " + settings.model());
            System.out.println("    Auth:   $" + envKey);
            System.out.println();
            System.out.println("  Run 'oh' to start an interactive session.");
            System.out.println("  Run 'oh doctor' to verify your setup.");
            return 0;
        }
    }

    @Command(name = "init", description = "Initialize a new OpenHarness workspace")
    static class InitCmd implements Callable<Integer> {
        @Option(names = {"-d", "--dir"}, description = "Target directory")
        private String dir = ".";

        @Override
        public Integer call() {
            java.nio.file.Path cwd = java.nio.file.Path.of(dir).toAbsolutePath().normalize();
            java.nio.file.Path ohDir = cwd.resolve(".openharness");

            try {
                java.nio.file.Files.createDirectories(ohDir);
                java.nio.file.Files.createDirectories(ohDir.resolve("skills"));
                java.nio.file.Files.createDirectories(ohDir.resolve("memory"));
                java.nio.file.Files.createDirectories(ohDir.resolve("autopilot"));

                java.nio.file.Path settingsFile = ohDir.resolve("settings.json");
                if (!java.nio.file.Files.exists(settingsFile)) {
                    java.nio.file.Files.writeString(settingsFile, "{\n  \"model\": \"claude-sonnet-4-6\"\n}\n");
                }

                System.out.println("Initialized OpenHarness workspace at: " + ohDir);
                System.out.println("Created .openharness/ with skills/, memory/, autopilot/");
                return 0;
            } catch (java.io.IOException e) {
                System.err.println("Failed to initialize: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "gateway", description = "ohmo gateway lifecycle management")
    static class GatewayCmd implements Callable<Integer> {
        @Option(names = {"--start"}, description = "Start the gateway")
        private boolean start;

        @Option(names = {"--stop"}, description = "Stop the gateway")
        private boolean stop;

        @Option(names = {"--status"}, description = "Show gateway status")
        private boolean status;

        @Override
        public Integer call() {
            if (status || (!start && !stop)) {
                System.out.println("Gateway status: checking...");
                System.out.println("  Use --start or --stop to manage the ohmo gateway.");
                System.out.println("  For full gateway functionality, use: oh ohmo gateway");
                return 0;
            }

            if (start) {
                System.out.println("Starting ohmo gateway...");
                System.out.println("  Use 'oh ohmo gateway --start' for full gateway control.");
            }

            if (stop) {
                System.out.println("Stopping ohmo gateway...");
                System.out.println("  Use 'oh ohmo gateway --stop' for full gateway control.");
            }

            return 0;
        }
    }
}
