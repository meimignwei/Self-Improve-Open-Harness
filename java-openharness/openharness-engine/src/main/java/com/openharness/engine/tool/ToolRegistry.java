package com.openharness.engine.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Map tool names to implementations.
 * Java equivalent of Python's ToolRegistry.
 */
public class ToolRegistry {

    private final Map<String, BaseTool<?>> tools = new ConcurrentHashMap<>();

    public void register(BaseTool<?> tool) {
        tools.put(tool.name(), tool);
    }

    @SuppressWarnings("unchecked")
    public <T> BaseTool<T> get(String name) {
        return (BaseTool<T>) tools.get(name);
    }

    public List<BaseTool<?>> listTools() {
        return new ArrayList<>(tools.values());
    }

    public List<JsonNode> toApiSchema() {
        return tools.values().stream()
                .map(BaseTool::toApiSchema)
                .toList();
    }
}
