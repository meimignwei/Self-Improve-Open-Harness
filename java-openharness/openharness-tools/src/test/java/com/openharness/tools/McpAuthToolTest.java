package com.openharness.tools;

import com.openharness.common.McpClient;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpAuthToolTest {

    @Test
    void shouldPersistBearerAuth(@TempDir Path tempDir) {
        McpClient client = new McpClient() {
            @Override public java.util.List<McpClient.McpResourceInfo> listResources(String serverName) { return java.util.List.of(); }
            @Override public String readResource(String serverName, String uri) { return ""; }
            @Override public String callTool(String serverName, String toolName, com.fasterxml.jackson.databind.JsonNode args) { return ""; }
            @Override public java.util.List<McpClient.McpPromptInfo> listPrompts(String serverName) { return java.util.List.of(); }
            @Override public String getPrompt(String serverName, String promptName, java.util.Map<String, String> arguments) { return ""; }
            @Override public void disconnect(String serverName) {}
        };

        Path configPath = tempDir.resolve("config.json");
        var tool = new McpAuthTool(client, configPath);
        var result = tool.execute(new McpAuthTool.Input("srv1", "bearer", "tok123", null),
                new ToolExecutionContext(tempDir));

        assertFalse(result.isError());
        assertTrue(result.content().contains("Updated auth"));
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
