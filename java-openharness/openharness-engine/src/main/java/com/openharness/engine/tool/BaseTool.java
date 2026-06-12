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

    public JsonNode inputSchema() {
        return buildRecordSchema(inputType);
    }

    public JsonNode toApiSchema() {
        var mapper = OpenHarnessObjectMapper.get();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("name", name);
        schema.put("description", description);
        schema.set("input_schema", inputSchema());
        return schema;
    }

    private static JsonNode buildRecordSchema(Class<?> inputType) {
        var mapper = OpenHarnessObjectMapper.get();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        java.util.List<String> required = new java.util.ArrayList<>();

        if (inputType != null && inputType.isRecord()) {
            for (java.lang.reflect.RecordComponent component : inputType.getRecordComponents()) {
                String fieldName = component.getName();
                Class<?> fieldType = component.getType();
                properties.set(fieldName, typeToSchema(fieldType, mapper));
                required.add(fieldName);
            }
        }

        schema.set("properties", properties);
        if (!required.isEmpty()) {
            schema.set("required", mapper.valueToTree(required));
        }
        return schema;
    }

    private static JsonNode typeToSchema(Class<?> type, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        if (type == String.class) {
            node.put("type", "string");
        } else if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) {
            node.put("type", "integer");
        } else if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            node.put("type", "number");
        } else if (type == boolean.class || type == Boolean.class) {
            node.put("type", "boolean");
        } else if (java.util.List.class.isAssignableFrom(type)) {
            node.put("type", "array");
        } else {
            node.put("type", "object");
        }
        return node;
    }
}
