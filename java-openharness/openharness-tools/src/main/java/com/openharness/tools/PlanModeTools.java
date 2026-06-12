package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Plan mode entry and exit tools.
 * Java equivalent of Python's enter_plan_mode / exit_plan_mode.
 */
public final class PlanModeTools {

    public static class EnterPlanModeTool extends BaseTool<Void> {
        public EnterPlanModeTool() {
            super("enter_plan_mode", "Enter plan mode to design an implementation approach before writing code.", Void.class);
        }

        @Override
        public ToolResult execute(Void args, ToolExecutionContext ctx) {
            ctx.metadata().put("plan_mode", true);
            return ToolResult.success("Entered plan mode. Design your approach in the plan file, then use exit_plan_mode to request approval.");
        }

        @Override public boolean isReadOnly(Void args) { return true; }
    }

    public static class ExitPlanModeTool extends BaseTool<Void> {
        public ExitPlanModeTool() {
            super("exit_plan_mode", "Exit plan mode and request user approval for the implementation plan.", Void.class);
        }

        @Override
        public ToolResult execute(Void args, ToolExecutionContext ctx) {
            ctx.metadata().put("plan_mode", false);
            ctx.metadata().put("plan_ready", true);
            return ToolResult.success("Plan mode exited. The plan has been written to the plan file and is ready for user review.");
        }

        @Override public boolean isReadOnly(Void args) { return true; }
    }
}
