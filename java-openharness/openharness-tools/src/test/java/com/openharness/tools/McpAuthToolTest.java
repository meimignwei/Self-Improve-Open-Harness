package com.openharness.tools;

import com.openharness.common.McpClient;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpAuthToolTest {

    @Test
    void shouldPersistBearerAuth(@TempDir Path tempDir) throws Exception {
        McpClient client = new McpClient() {
            @Override public java.util.List<McpClient.McpResourceInfo> listResources(String serverName) { return java.util.List.of(); }
            @Override public String readResource(String serverName, String uri) { return ""; }
            @Override public String callTool(String serverName, String toolName, com.fasterxml.jackson.databind.JsonNode args) { return ""; }
            @Override public java.util.List<McpClient.McpPromptInfo> listPrompts(String serverName) { return java.util.List.of(); }
            @Override public String getPrompt(String serverName, String promptName, java.util.Map<String, String> arguments) { return ""; }
            @Override public void disconnect(String serverName) {}
        };

        Path configPath = tempDir.resolve("config.json");

        // Pre-populate config with server entry (matching Python: server must exist)
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> servers = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("command", "test-cmd");
        server.put("args", java.util.List.of());
        server.put("env", new LinkedHashMap<>());
        servers.put("srv1", server);
        config.put("mcp_servers", servers);
        com.openharness.common.OpenHarnessObjectMapper.get()
                .writeValue(configPath.toFile(), config);

        var tool = new McpAuthTool(client, configPath);
        var result = tool.execute(new McpAuthTool.Input("srv1", "bearer", "tok123", null),
                new ToolExecutionContext(tempDir));

        assertFalse(result.isError());
        assertTrue(result.content().contains("Saved MCP auth"));
    }

    @Test
    void shouldRejectNullServerName() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpAuthTool.Input(null, "bearer", "v", null));
    }

    @Test
    void shouldRejectNullMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpAuthTool.Input("s", null, "v", null));
    }
}
