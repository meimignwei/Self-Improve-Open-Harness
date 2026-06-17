package com.openharness.tools;

import com.openharness.common.McpClient;
import com.openharness.common.ToolResult;
import com.openharness.config.AtomicFileWriter;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.extensions.mcp.McpClientManager;
import com.openharness.extensions.mcp.McpServerConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persist auth settings for an MCP server and reconnect active sessions.
 * Java equivalent of Python's McpAuthTool.
 * <p>
 * Auth modes by transport:
 * <ul>
 *   <li>stdio: env, bearer</li>
 *   <li>http/ws/streamable-http: header, bearer</li>
 * </ul>
 */
public class McpAuthTool extends BaseTool<McpAuthTool.Input> {

    private final McpClient mcpClient;
    private final Path configPath;

    public McpAuthTool(McpClient mcpClient, Path configPath) {
        super("mcp_auth", "Configure auth for an MCP server and reconnect active sessions when possible.", Input.class);
        this.mcpClient = mcpClient;
        this.configPath = configPath;
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        try {
            Map<String, Object> config = loadConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> servers = (Map<String, Object>) config.getOrDefault(
                    "mcp_servers", new LinkedHashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> server = (Map<String, Object>) servers.get(arguments.serverName());

            if (server == null) {
                // Try to get from the in-memory manager as fallback (matching Python)
                if (mcpClient instanceof McpClientManager mcpManager) {
                    McpServerConfig sc = mcpManager.getServerConfig(arguments.serverName());
                    if (sc == null) {
                        return ToolResult.error("Unknown MCP server: " + arguments.serverName());
                    }
                    // Build a mutable map from the server config for updating
                    server = serverConfigToMap(sc);
                } else {
                    return ToolResult.error("Unknown MCP server: " + arguments.serverName());
                }
            }

            boolean isStdio = server.containsKey("command")
                    || (server.get("transport") != null && "stdio".equals(server.get("transport")));

            if (isStdio) {
                if (!Set.of("env", "bearer").contains(arguments.mode())) {
                    return ToolResult.error("stdio MCP auth supports env or bearer modes");
                }
                String envKey = arguments.key() != null && !arguments.key().isBlank()
                        ? arguments.key() : "MCP_AUTH_TOKEN";
                @SuppressWarnings("unchecked")
                Map<String, String> env = (Map<String, String>) server.getOrDefault("env", new LinkedHashMap<>());
                env = new LinkedHashMap<>(env); // mutable copy
                env.put(envKey, "bearer".equals(arguments.mode())
                        ? "Bearer " + arguments.value() : arguments.value());
                server.put("env", env);
            } else {
                // http / ws / streamable-http
                if (!Set.of("header", "bearer").contains(arguments.mode())) {
                    return ToolResult.error("http/ws MCP auth supports header or bearer modes");
                }
                String headerKey = arguments.key() != null && !arguments.key().isBlank()
                        ? arguments.key() : "Authorization";
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) server.getOrDefault("headers", new LinkedHashMap<>());
                headers = new LinkedHashMap<>(headers); // mutable copy
                headers.put(headerKey, "bearer".equals(arguments.mode()) && "Authorization".equals(headerKey)
                        ? "Bearer " + arguments.value() : arguments.value());
                server.put("headers", headers);
            }

            servers.put(arguments.serverName(), server);
            config.put("mcp_servers", servers);
            AtomicFileWriter.writeJson(configPath, config);

            // Reconnect: update in-memory config and reconnect all (matching Python)
            if (mcpClient instanceof McpClientManager mcpManager) {
                try {
                    McpServerConfig updatedConfig = mapToServerConfig(arguments.serverName(), server);
                    if (updatedConfig != null) {
                        mcpManager.updateServerConfig(arguments.serverName(), updatedConfig);
                    }
                    mcpManager.reconnectAll();
                } catch (Exception e) {
                    return ToolResult.error("Saved MCP auth for " + arguments.serverName()
                            + ", but reconnect failed: " + e.getMessage());
                }
            } else if (mcpClient != null) {
                // Fallback: just disconnect (old behavior)
                mcpClient.disconnect(arguments.serverName());
            }

            return ToolResult.success("Saved MCP auth for " + arguments.serverName());
        } catch (Exception e) {
            return ToolResult.error("Failed to update MCP auth: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() throws Exception {
        if (!Files.exists(configPath)) return new LinkedHashMap<>();
        return com.openharness.common.OpenHarnessObjectMapper.get()
                .readValue(configPath.toFile(), LinkedHashMap.class);
    }

    /**
     * Convert a McpServerConfig to a mutable Map for auth updates.
     */
    private static Map<String, Object> serverConfigToMap(McpServerConfig sc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", sc.name());
        return switch (sc) {
            case McpServerConfig.StdioConfig s -> {
                map.put("command", s.command());
                map.put("args", s.args());
                map.put("env", new LinkedHashMap<>(s.env() != null ? s.env() : Map.of()));
                if (s.cwd() != null) map.put("cwd", s.cwd());
                yield map;
            }
            case McpServerConfig.HttpConfig h -> {
                map.put("url", h.url());
                map.put("headers", new LinkedHashMap<>(h.headers() != null ? h.headers() : Map.of()));
                yield map;
            }
            case McpServerConfig.WebSocketConfig w -> {
                map.put("url", w.url());
                map.put("headers", new LinkedHashMap<>(w.headers() != null ? w.headers() : Map.of()));
                yield map;
            }
            case McpServerConfig.StreamableHttpConfig sh -> {
                map.put("url", sh.url());
                map.put("headers", new LinkedHashMap<>(sh.headers() != null ? sh.headers() : Map.of()));
                yield map;
            }
        };
    }

    /**
     * Reconstruct a McpServerConfig from the config map after auth updates.
     */
    @SuppressWarnings("unchecked")
    private static McpServerConfig mapToServerConfig(String name, Map<String, Object> map) {
        if (map.containsKey("command")) {
            String command = (String) map.get("command");
            List<String> args = map.containsKey("args") ? (List<String>) map.get("args") : List.of();
            Map<String, String> env = map.containsKey("env")
                    ? (Map<String, String>) map.get("env") : Map.of();
            String cwd = (String) map.get("cwd");
            return new McpServerConfig.StdioConfig(name, command, args, env, cwd);
        }
        if (map.containsKey("url")) {
            String url = (String) map.get("url");
            Map<String, String> headers = map.containsKey("headers")
                    ? (Map<String, String>) map.get("headers") : Map.of();
            // Determine transport type from a transport field if present, default to HttpConfig
            String transport = (String) map.getOrDefault("transport", "http");
            return switch (transport) {
                case "ws", "websocket" -> new McpServerConfig.WebSocketConfig(name, url, headers, Map.of());
                case "streamable-http", "streamable" ->
                        new McpServerConfig.StreamableHttpConfig(name, url, headers, Map.of());
                default -> new McpServerConfig.HttpConfig(name, url, headers, Map.of());
            };
        }
        return null;
    }

    public record Input(String serverName, String mode, String value, String key) {
        public Input {
            if (serverName == null || serverName.isBlank()) {
                throw new IllegalArgumentException("serverName is required");
            }
            if (mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("mode is required (bearer, header, env)");
            }
        }
    }
}
