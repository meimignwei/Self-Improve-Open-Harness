package com.openharness.engine.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for all OpenHarness tools.
 * Java equivalent of Python's BaseTool.
 */
public abstract class BaseTool<I> {

    private final String name;
    private final String description;
    private final Class<I> inputType;

    protected BaseTool(String name, String description, Class<I> inputType) {
        this.name = name;
        this.description = description;
        this.inputType = inputType;
    }

    public String name() { return name; }
    public String description() { return description; }
    public Class<I> inputType() { return inputType; }

    public abstract ToolResult execute(I arguments, ToolExecutionContext context);

    public boolean isReadOnly(I arguments) {
        return false;
    }

    public JsonNode toApiSchema() {
        var mapper = OpenHarnessObjectMapper.get();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("name", name);
        schema.put("description", description);
        schema.set("input_schema", mapper.valueToTree(
                java.util.Map.of("type", "object")));
        return schema;
    }
}
