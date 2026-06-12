package com.openharness.extensions.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * MCP server configuration — three transport types.
 * Java equivalent of Python's McpServerConfig sealed interface.
 */
public sealed interface McpServerConfig
        permits McpServerConfig.StdioConfig, McpServerConfig.HttpConfig,
                McpServerConfig.WebSocketConfig {

    String name();

    record StdioConfig(String name, String command, List<String> args,
                       Map<String, String> env, String cwd) implements McpServerConfig {}

    record HttpConfig(String name, String url,
                      Map<String, String> headers) implements McpServerConfig {}

    record WebSocketConfig(String name, String url,
                           Map<String, String> headers) implements McpServerConfig {}
}
