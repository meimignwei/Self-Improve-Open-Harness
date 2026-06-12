package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlanModeToolsTest {

    @Test
    void enterPlanModeShouldSetMetadata() {
        var tool = new PlanModeTools.EnterPlanModeTool();
        var ctx = new ToolExecutionContext(Path.of("."));
        ToolResult result = tool.execute(null, ctx);

        assertFalse(result.isError());
        assertEquals(Boolean.TRUE, ctx.metadata().get("plan_mode"));
        assertTrue(tool.isReadOnly(null));
        assertEquals("enter_plan_mode", tool.name());
    }

    @Test
    void exitPlanModeShouldSetMetadata() {
        var tool = new PlanModeTools.ExitPlanModeTool();
        var ctx = new ToolExecutionContext(Path.of("."));
        ctx.metadata().put("plan_mode", true);
        ToolResult result = tool.execute(null, ctx);

        assertFalse(result.isError());
        assertEquals(Boolean.FALSE, ctx.metadata().get("plan_mode"));
        assertEquals(Boolean.TRUE, ctx.metadata().get("plan_ready"));
        assertTrue(tool.isReadOnly(null));
        assertEquals("exit_plan_mode", tool.name());
    }
}
