package com.openharness.tools;

import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BriefToolTest {

    private final BriefTool tool = new BriefTool();
    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."));

    @Test
    void shouldTruncateLongText() {
        String text = "a".repeat(500);
        var result = tool.execute(new BriefTool.Input(text, 200), ctx);
        assertFalse(result.isError());
        assertEquals(203, result.content().length());
        assertTrue(result.content().endsWith("..."));
    }

    @Test
    void shouldNotTruncateShortText() {
        var result = tool.execute(new BriefTool.Input("hello", 200), ctx);
        assertFalse(result.isError());
        assertEquals("hello", result.content());
    }

    @Test
    void shouldApplyMinMaxBounds() {
        var result = tool.execute(new BriefTool.Input("hello", 5), ctx);
        assertFalse(result.isError());
        assertEquals("hello", result.content());
    }

    @Test
    void isReadOnlyShouldReturnTrue() {
        assertTrue(tool.isReadOnly(new BriefTool.Input("x", 100)));
    }

    @Test
    void nameShouldBeBrief() {
        assertEquals("brief", tool.name());
    }
}
