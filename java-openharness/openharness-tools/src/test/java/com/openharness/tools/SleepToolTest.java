package com.openharness.tools;

import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SleepToolTest {

    private final SleepTool tool = new SleepTool();
    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."));

    @Test
    void shouldSleepAndReturnSuccess() {
        long start = System.currentTimeMillis();
        var result = tool.execute(new SleepTool.Input(0.1), ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.isError());
        assertTrue(elapsed >= 50, "Should sleep at least 50ms");
        assertTrue(result.content().contains("0.1"));
    }

    @Test
    void shouldClampNegativeToZero() {
        long start = System.currentTimeMillis();
        var result = tool.execute(new SleepTool.Input(-1), ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.isError());
        assertTrue(elapsed < 100, "Should not sleep for negative values");
    }

    @Test
    void shouldClampAboveThirty() {
        var input = new SleepTool.Input(100);
        assertEquals(30.0, input.seconds());
    }

    @Test
    void nameShouldBeSleep() {
        assertEquals("sleep", tool.name());
    }
}
