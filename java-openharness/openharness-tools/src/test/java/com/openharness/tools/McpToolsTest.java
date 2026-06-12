package com.openharness.tools;

import com.openharness.common.McpClient;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpToolsTest {

    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."));

    @Test
    void listMcpResourcesShouldReturnEmptyMessage() {
        McpClient client = new McpClient() {
            @Override public List<McpClient.McpResourceInfo> listResources(String serverName) { return List.of(); }
            @Override public String readResource(String serverName, String uri) { return ""; }
            @Override public String callTool(String serverName, String toolName, com.fasterxml.jackson.databind.JsonNode args) { return ""; }
            @Override public void disconnect(String serverName) {}
        };

        var tool = new McpTools.ListMcpResourcesTool(client);
        var result = tool.execute(new McpTools.ListMcpResourcesTool.Input("test"), ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("No resources found"));
    }

    @Test
    void listMcpResourcesShouldFormatResults() {
        McpClient client = new McpClient() {
            @Override public List<McpClient.McpResourceInfo> listResources(String serverName) {
                return List.of(new McpClient.McpResourceInfo("test", "readme", "file:///readme.md", "Project readme"));
            }
            @Override public String readResource(String serverName, String uri) { return ""; }
            @Override public String callTool(String serverName, String toolName, com.fasterxml.jackson.databind.JsonNode args) { return ""; }
            @Override public void disconnect(String serverName) {}
        };

        var tool = new McpTools.ListMcpResourcesTool(client);
        var result = tool.execute(new McpTools.ListMcpResourcesTool.Input("test"), ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("readme"));
        assertTrue(result.content().contains("file:///readme.md"));
    }

    @Test
    void readMcpResourceShouldReturnContent() {
        McpClient client = new McpClient() {
            @Override public List<McpClient.McpResourceInfo> listResources(String serverName) { return List.of(); }
            @Override public String readResource(String serverName, String uri) { return "hello world"; }
            @Override public String callTool(String serverName, String toolName, com.fasterxml.jackson.databind.JsonNode args) { return ""; }
            @Override public void disconnect(String serverName) {}
        };

        var tool = new McpTools.ReadMcpResourceTool(client);
        var result = tool.execute(new McpTools.ReadMcpResourceTool.Input("test", "file:///a.txt"), ctx);

        assertFalse(result.isError());
        assertEquals("hello world", result.content());
    }

    @Test
    void listMcpResourcesIsReadOnly() {
        McpClient client = new McpClient() {
            @Override public List<McpClient.McpResourceInfo> listResources(String serverName) { return List.of(); }
            @Override public String readResource(String serverName, String uri) { return ""; }
            @Override public String callTool(String serverName, String toolName, com.fasterxml.jackson.databind.JsonNode args) { return ""; }
            @Override public void disconnect(String serverName) {}
        };
        assertTrue(new McpTools.ListMcpResourcesTool(client).isReadOnly(
                new McpTools.ListMcpResourcesTool.Input("s")));
    }
}
