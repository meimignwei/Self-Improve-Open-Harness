package com.openharness.extensions.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.config.Paths;
import com.openharness.extensions.mcp.McpServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Discovers and loads plugins from user and project directories.
 * Java equivalent of Python's PluginLoader.
 */
public class PluginLoader {

    private static final Logger LOG = Logger.getLogger(PluginLoader.class.getName());
    private static final String PLUGIN_JSON = "plugin.json";
    private static final String CLAUDE_PLUGIN_JSON = ".claude-plugin/plugin.json";

    private final ObjectMapper mapper;

    public PluginLoader() {
        this.mapper = OpenHarnessObjectMapper.get();
    }

    /**
     * Load all plugins from user dir, project dir, and enabled plugin paths.
     */
    public List<LoadedPlugin> loadAll(Path cwd, java.util.Map<String, Boolean> enabledPlugins) {
        List<LoadedPlugin> plugins = new ArrayList<>();

        // 1. User plugins (~/.openharness/plugins/)
        Path userPluginsDir = Paths.homePluginsDir();
        if (Files.isDirectory(userPluginsDir)) {
            loadFromDir(plugins, userPluginsDir, "user");
        }

        // 2. Project plugins (.openharness/plugins/)
        Path projectPluginsDir = cwd.resolve(".openharness/plugins");
        if (Files.isDirectory(projectPluginsDir)) {
            loadFromDir(plugins, projectPluginsDir, "project");
        }

        return plugins;
    }

    private void loadFromDir(List<LoadedPlugin> plugins, Path dir, String source) {
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(pluginDir -> {
                try {
                    PluginManifest manifest = loadManifest(pluginDir);
                    if (manifest != null) {
                        plugins.add(new LoadedPlugin(manifest, source));
                        LOG.fine("Loaded plugin: " + manifest.name() + " from " + source);
                    }
                } catch (IOException e) {
                    LOG.warning("Failed to load plugin from " + pluginDir + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.fine("Plugin directory not found or not readable: " + dir);
        }
    }

    /**
     * Load plugin manifest from plugin.json or .claude-plugin/plugin.json.
     */
    public PluginManifest loadManifest(Path pluginDir) throws IOException {
        Path manifestPath = pluginDir.resolve(PLUGIN_JSON);
        if (!Files.exists(manifestPath)) {
            manifestPath = pluginDir.resolve(CLAUDE_PLUGIN_JSON);
        }
        if (!Files.exists(manifestPath)) return null;

        String json = Files.readString(manifestPath);
        return mapper.readValue(json, PluginManifest.class);
    }

    /**
     * Load MCP server configs from a plugin's mcpServersDir (e.g., "mcp_servers").
     * Reads mcp.json files, with .mcp.json as fallback.
     * Returns a map of server name to McpServerConfig.
     */
    @SuppressWarnings("unchecked")
    public Map<String, McpServerConfig> loadPluginMcp(LoadedPlugin plugin) throws IOException {
        if (plugin.manifest().mcpServersDir() == null) return Map.of();

        Path mcpDir = plugin.manifest().pluginDir().resolve(plugin.manifest().mcpServersDir());
        if (!Files.isDirectory(mcpDir)) return Map.of();

        // Look for mcp.json or .mcp.json
        Path mcpFile = mcpDir.resolve("mcp.json");
        if (!Files.exists(mcpFile)) {
            mcpFile = mcpDir.resolve(".mcp.json");
        }
        if (!Files.exists(mcpFile)) return Map.of();

        return loadMcpJsonFile(mcpFile);
    }

    /**
     * Parse an MCP JSON config file in the shape {"mcpServers": {name: config}}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, McpServerConfig> loadMcpJsonFile(Path file) throws IOException {
        Map<String, Object> root = OpenHarnessObjectMapper.get()
                .readValue(file.toFile(), LinkedHashMap.class);
        Map<String, Object> servers = (Map<String, Object>)
                root.getOrDefault("mcpServers", root.getOrDefault("mcp_servers", Map.of()));
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        for (var entry : servers.entrySet()) {
            Map<String, Object> cfg = (Map<String, Object>) entry.getValue();
            McpServerConfig parsed = parseMcpServerConfig(entry.getKey(), cfg);
            if (parsed != null) {
                result.put(entry.getKey(), parsed);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static McpServerConfig parseMcpServerConfig(String name, Map<String, Object> cfg) {
        String transport = (String) cfg.getOrDefault("transport", "stdio");
        Map<String, Object> auth = (Map<String, Object>) cfg.get("auth");
        if (auth == null) auth = Map.of();
        try {
            return switch (transport) {
                case "stdio" -> {
                    String command = (String) cfg.get("command");
                    if (command == null || command.isBlank()) yield null;
                    List<String> args = (List<String>) cfg.getOrDefault("args", List.of());
                    Map<String, String> env = (Map<String, String>) cfg.getOrDefault("env", Map.of());
                    String cwd = (String) cfg.get("cwd");
                    yield new McpServerConfig.StdioConfig(name, command, args, env, cwd);
                }
                case "http", "sse" -> {
                    String url = (String) cfg.get("url");
                    if (url == null || url.isBlank()) yield null;
                    Map<String, String> headers = (Map<String, String>) cfg.getOrDefault("headers", Map.of());
                    yield new McpServerConfig.HttpConfig(name, url, headers, auth);
                }
                case "ws", "websocket" -> {
                    String url = (String) cfg.get("url");
                    if (url == null || url.isBlank()) yield null;
                    Map<String, String> headers = (Map<String, String>) cfg.getOrDefault("headers", Map.of());
                    yield new McpServerConfig.WebSocketConfig(name, url, headers, auth);
                }
                case "streamable-http", "streamable_http" -> {
                    String url = (String) cfg.get("url");
                    if (url == null || url.isBlank()) yield null;
                    Map<String, String> headers = (Map<String, String>) cfg.getOrDefault("headers", Map.of());
                    yield new McpServerConfig.StreamableHttpConfig(name, url, headers, auth);
                }
                default -> null;
            };
        } catch (Exception e) {
            LOG.warning("Failed to parse MCP config for " + name + ": " + e.getMessage());
            return null;
        }
    }

    public record LoadedPlugin(PluginManifest manifest, String source) {
        public String name() { return manifest.name(); }
        public Path skillsDir() {
            if (manifest.skillsDir() != null) {
                return manifest.pluginDir().resolve(manifest.skillsDir());
            }
            return null;
        }
        public List<com.openharness.extensions.skills.SkillDefinition> skills() { return List.of(); }
        public List<String> agents() { return List.of(); }
    }
}
