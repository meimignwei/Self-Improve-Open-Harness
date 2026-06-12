package com.openharness.common;

import java.util.List;

/**
 * Minimal MCP client interface for tools.
 */
import com.fasterxml.jackson.databind.JsonNode;

public interface McpClient {
    List<McpResourceInfo> listResources(String serverName);
    String readResource(String serverName, String uri);
    String callTool(String serverName, String toolName, JsonNode args);
    void disconnect(String serverName);

    record McpResourceInfo(String serverName, String name, String uri, String description) {}
}
