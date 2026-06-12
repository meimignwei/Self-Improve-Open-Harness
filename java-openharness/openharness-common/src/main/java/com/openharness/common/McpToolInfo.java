package com.openharness.common;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolInfo(String serverName, String name, String description, JsonNode inputSchema) {}
