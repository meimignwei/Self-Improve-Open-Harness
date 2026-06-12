package com.openharness.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Definition of a tool that can be passed to the LLM API.
 * Java equivalent of Python's tool definition dict.
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, JsonNode> inputSchema) {}
