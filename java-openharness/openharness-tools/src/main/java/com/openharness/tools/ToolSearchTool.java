package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.engine.tool.ToolRegistry;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Searches the tool registry by substring match on name or description.
 */
public class ToolSearchTool extends BaseTool<ToolSearchTool.Input> {

    private final ToolRegistry toolRegistry;

    public ToolSearchTool(ToolRegistry toolRegistry) {
        super("tool_search", "Searches available tools by name or description substring.", Input.class);
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        String query = arguments.query().toLowerCase(Locale.ROOT);
        var matches = toolRegistry.listTools().stream()
                .filter(t -> t.name().toLowerCase(Locale.ROOT).contains(query)
                        || t.description().toLowerCase(Locale.ROOT).contains(query))
                .map(t -> t.name() + ": " + t.description())
                .collect(Collectors.joining("\n"));
        if (matches.isEmpty()) {
            return ToolResult.success("(no matches)");
        }
        return ToolResult.success(matches);
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    public record Input(String query) {
        public Input {
            if (query == null) query = "";
        }
    }
}
