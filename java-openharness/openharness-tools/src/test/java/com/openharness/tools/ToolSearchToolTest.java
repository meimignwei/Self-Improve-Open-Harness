package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.engine.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolSearchToolTest {

    @Test
    void shouldFindToolByName() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BriefTool());
        registry.register(new SleepTool());

        var tool = new ToolSearchTool(registry);
        ToolResult result = tool.execute(new ToolSearchTool.Input("brief"),
                new ToolExecutionContext(Path.of(".")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("brief"));
    }

    @Test
    void shouldFindToolByDescription() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BriefTool());

        var tool = new ToolSearchTool(registry);
        ToolResult result = tool.execute(new ToolSearchTool.Input("shorten"),
                new ToolExecutionContext(Path.of(".")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("brief"));
    }

    @Test
    void shouldReturnNoResults() {
        ToolRegistry registry = new ToolRegistry();
        var tool = new ToolSearchTool(registry);
        ToolResult result = tool.execute(new ToolSearchTool.Input("nonexistent"),
                new ToolExecutionContext(Path.of(".")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("(no matches)"));
    }

    @Test
    void isReadOnlyShouldReturnTrue() {
        assertTrue(new ToolSearchTool(new ToolRegistry()).isReadOnly(
                new ToolSearchTool.Input("x")));
    }
}
