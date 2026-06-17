package com.openharness.common;

import java.util.List;
import java.util.Map;

/**
 * MCP client interface for tools, resources, and prompts.
 */
import com.fasterxml.jackson.databind.JsonNode;

public interface McpClient {
    List<McpResourceInfo> listResources(String serverName);

    /** List MCP resources from all connected servers (matching Python's list_resources). */
    default List<McpResourceInfo> listResources() {
        return List.of();
    }

    String readResource(String serverName, String uri);
    String callTool(String serverName, String toolName, JsonNode args);

    /** List available prompts from an MCP server. */
    List<McpPromptInfo> listPrompts(String serverName);

    /** Get a resolved prompt from an MCP server. */
    String getPrompt(String serverName, String promptName, Map<String, String> arguments);

    void disconnect(String serverName);

    record McpResourceInfo(String serverName, String name, String uri, String description) {}

    record McpPromptInfo(String serverName, String name, String description,
                          List<McpPromptArgInfo> arguments) {}

    record McpPromptArgInfo(String name, String description, boolean required) {}
}
