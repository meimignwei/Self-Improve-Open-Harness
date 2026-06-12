package com.openharness.extensions.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolInfo(String serverName, String name, String description, JsonNode inputSchema) {}
