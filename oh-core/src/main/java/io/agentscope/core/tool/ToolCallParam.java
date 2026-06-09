package io.agentscope.core.tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;
public class ToolCallParam {

    private final String toolName;
    private final Map<String, Object> arguments;

    public ToolCallParam(String toolName, Map<String, Object> arguments) {
        this.toolName = toolName;
        this.arguments = arguments != null ? Collections.unmodifiableMap(arguments) : Collections.emptyMap();
    }

    public String getToolName() {
        return toolName;
    }

    public String getString(String key) {
        Object value = arguments.get(key);
        return value != null ? value.toString() : null;
    }

    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value != null ? value : defaultValue;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object value = arguments.get(key);
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return Collections.emptyList();
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
