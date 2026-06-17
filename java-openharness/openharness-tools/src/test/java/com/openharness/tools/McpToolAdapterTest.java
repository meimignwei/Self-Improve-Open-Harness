package com.openharness.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.McpClient;
import com.openharness.common.McpToolInfo;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpToolAdapterTest {

    @Test
    void shouldDelegateToMcpClient() {
        McpClient client = new McpClient() {
            @Override public java.util.List<McpClient.McpResourceInfo> listResources(String serverName) { return java.util.List.of(); }
            @Override public String readResource(String serverName, String uri) { return ""; }
            @Override public String callTool(String serverName, String toolName, com.fasterxml.jackson.databind.JsonNode args) { return "mcp-result"; }
            @Override public java.util.List<McpClient.McpPromptInfo> listPrompts(String serverName) { return java.util.List.of(); }
            @Override public String getPrompt(String serverName, String promptName, java.util.Map<String, String> arguments) { return ""; }
            @Override public void disconnect(String serverName) {}
        };

        var info = new McpToolInfo("my-server", "do_thing", "does a thing", null);
        var adapter = new McpToolAdapter(client, info);
        var result = adapter.execute(new ObjectNode(null), new ToolExecutionContext(Path.of(".")));

        assertFalse(result.isError());
        assertEquals("mcp-result", result.content());
    }

    @Test
    void nameShouldBePrefixedAndSanitized() {
        McpClient client = new McpClient() {
            @Override public java.util.List<McpClient.McpResourceInfo> listResources(String serverName) { return java.util.List.of(); }
            @Override public String readResource(String serverName, String uri) { return ""; }
            @Override public String callTool(String serverName, String toolName, com.fasterxml.jackson.databind.JsonNode args) { return ""; }
            @Override public java.util.List<McpClient.McpPromptInfo> listPrompts(String serverName) { return java.util.List.of(); }
            @Override public String getPrompt(String serverName, String promptName, java.util.Map<String, String> arguments) { return ""; }
            @Override public void disconnect(String serverName) {}
        };

        var info = new McpToolInfo("srv", "tool-1", "desc", null);
        var adapter = new McpToolAdapter(client, info);
        assertEquals("mcp_srv_tool_1", adapter.name());
    }
}
