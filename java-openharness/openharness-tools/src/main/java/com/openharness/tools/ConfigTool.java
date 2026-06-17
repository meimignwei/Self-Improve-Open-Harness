package com.openharness.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;
import com.openharness.config.Settings;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.lang.reflect.Method;

/**
 * Read or update OpenHarness settings.
 */
public class ConfigTool extends BaseTool<ConfigTool.Input> {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    public ConfigTool() {
        super("config", "Read or update OpenHarness settings. action=show dumps settings JSON; action=set updates a key using dot-path notation.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Settings settings = Settings.load();
        String action = arguments.action();
        if ("show".equals(action)) {
            try {
                return ToolResult.success(MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(settings));
            } catch (Exception e) {
                return ToolResult.error("Failed to serialize settings: " + e.getMessage());
            }
        }
        if ("set".equals(action)) {
            if (arguments.key() == null || arguments.value() == null) {
                return ToolResult.error("Usage: action=set with key and value");
            }
            String result = setByPath(settings, arguments.key(), arguments.value());
            if (result.startsWith("Updated")) {
                settings.save();
            }
            return result.startsWith("Error") ? ToolResult.error(result) : ToolResult.success(result);
        }
        return ToolResult.error("Unknown action: " + action + ". Use 'show' or 'set'.");
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return "show".equals(arguments.action());
    }

    private String setByPath(Object target, String path, String valueStr) {
        String[] parts = path.split("\\.");
        try {
            for (int i = 0; i < parts.length - 1; i++) {
                target = callGetter(target, parts[i]);
                if (target == null) return "Unknown config key: " + path;
            }
            String leaf = parts[parts.length - 1];
            Object current = callGetter(target, leaf);
            Object coerced = coerce(valueStr, current);
            callSetter(target, leaf, coerced);
            return "Updated " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private Object callGetter(Object obj, String name) throws Exception {
        String getter = name;
        Method m = findMethod(obj.getClass(), getter, 0);
        if (m == null) {
            getter = "get" + capitalize(name);
            m = findMethod(obj.getClass(), getter, 0);
        }
        if (m == null) {
            getter = "is" + capitalize(name);
            m = findMethod(obj.getClass(), getter, 0);
        }
        if (m == null) throw new NoSuchMethodException("No getter for " + name);
        return m.invoke(obj);
    }

    private void callSetter(Object obj, String name, Object value) throws Exception {
        String setter = "set" + capitalize(name);
        for (Method m : obj.getClass().getMethods()) {
            if (m.getName().equals(setter) && m.getParameterCount() == 1) {
                m.invoke(obj, value);
                return;
            }
        }
        throw new NoSuchMethodException("No setter for " + name);
    }

    private Method findMethod(Class<?> clazz, String name, int paramCount) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        return null;
    }

    private Object coerce(String value, Object current) {
        if (current instanceof Boolean) {
            return value.strip().toLowerCase().matches("1|true|yes|on");
        }
        if (current instanceof Integer) {
            return Integer.parseInt(value.strip());
        }
        if (current instanceof Double) {
            return Double.parseDouble(value.strip());
        }
        if (current instanceof Long) {
            return Long.parseLong(value.strip());
        }
        if (current instanceof Float) {
            return Float.parseFloat(value.strip());
        }
        return value;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public record Input(String action, String key, String value) {
        public Input {
            if (action == null) action = "show";
        }
    }
}
