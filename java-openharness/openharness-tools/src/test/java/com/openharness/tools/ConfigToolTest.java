package com.openharness.tools;

import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigToolTest {

    private final ConfigTool tool = new ConfigTool();
    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."));

    @Test
    void showShouldReturnJson() {
        var result = tool.execute(new ConfigTool.Input("show", null, null), ctx);
        assertFalse(result.isError());
        assertTrue(result.content().contains("model"));
    }

    @Test
    void setShouldUpdateModel() {
        var result = tool.execute(new ConfigTool.Input("set", "model", "gpt-4o"), ctx);
        assertFalse(result.isError());
        assertTrue(result.content().contains("Updated"));
    }

    @Test
    void setShouldRequireKeyAndValue() {
        var result = tool.execute(new ConfigTool.Input("set", null, "v"), ctx);
        assertTrue(result.isError());
    }

    @Test
    void unknownActionShouldError() {
        var result = tool.execute(new ConfigTool.Input("unknown", null, null), ctx);
        assertTrue(result.isError());
    }

    @Test
    void isReadOnlyForShow() {
        assertTrue(tool.isReadOnly(new ConfigTool.Input("show", null, null)));
    }

    @Test
    void isNotReadOnlyForSet() {
        assertFalse(tool.isReadOnly(new ConfigTool.Input("set", "x", "y")));
    }
}
