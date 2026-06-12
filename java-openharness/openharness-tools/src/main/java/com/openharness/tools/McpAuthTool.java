package com.openharness.tools;

import com.openharness.common.McpClient;
import com.openharness.common.ToolResult;
import com.openharness.config.AtomicFileWriter;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Persist auth settings for an MCP server.
 */
public class McpAuthTool extends BaseTool<McpAuthTool.Input> {

    private final McpClient mcpClient;
    private final Path configPath;

    public McpAuthTool(McpClient mcpClient, Path configPath) {
        super("mcp_auth", "Configure authentication for an MCP server (bearer, header, or env mode).", Input.class);
        this.mcpClient = mcpClient;
        this.configPath = configPath;
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        try {
            Map<String, Object> config = loadConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> servers = (Map<String, Object>) config.getOrDefault("mcp_servers", new java.util.LinkedHashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> server = (Map<String, Object>) servers.getOrDefault(arguments.serverName(), new java.util.LinkedHashMap<>());

            switch (arguments.mode()) {
                case "bearer" -> server.put("auth", Map.of("type", "bearer", "token", arguments.value()));
                case "header" -> server.put("auth", Map.of("type", "header", "key", arguments.key(), "value", arguments.value()));
                case "env" -> server.put("auth", Map.of("type", "env", "key", arguments.key(), "value", arguments.value()));
                default -> { return ToolResult.error("Unknown auth mode: " + arguments.mode()); }
            }

            servers.put(arguments.serverName(), server);
            config.put("mcp_servers", servers);
            AtomicFileWriter.writeJson(configPath, config);

            // Trigger reconnect
            if (mcpClient != null) {
                mcpClient.disconnect(arguments.serverName());
            }

            return ToolResult.success("Updated auth for MCP server '" + arguments.serverName() + "' (mode: " + arguments.mode() + ").");
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
        if (!Files.exists(configPath)) return new java.util.LinkedHashMap<>();
        return com.openharness.common.OpenHarnessObjectMapper.get()
                .readValue(configPath.toFile(), java.util.LinkedHashMap.class);
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
