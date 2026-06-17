package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.config.Settings;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.permissions.PermissionMode;

/**
 * Plan mode entry and exit tools.
 * Java equivalent of Python's enter_plan_mode / exit_plan_mode tools.
 * <p>
 * Both tools persist the permission mode change to settings.json
 * (matching Python's behavior) AND update runtime metadata so the
 * engine can react to the mode change immediately without restarting.
 * <p>
 * Persistence ensures plan mode survives restarts and is visible
 * to other tool invocations that read settings from disk.
 */
public final class PlanModeTools {

    /**
     * Switch settings permission mode to plan.
     * Python equivalent: enter_plan_mode_tool.py
     */
    public static class EnterPlanModeTool extends BaseTool<Void> {
        public EnterPlanModeTool() {
            super("enter_plan_mode",
                    "Switch permission mode to plan.",
                    Void.class);
        }

        @Override
        public ToolResult execute(Void args, ToolExecutionContext ctx) {
            // 1. Persist to settings.json (matches Python: load → set mode → save)
            Settings settings = Settings.load();
            settings.permission().setMode(PermissionMode.PLAN.value());
            settings.save();

            // 2. Update runtime metadata for immediate engine awareness
            ctx.metadata().put("plan_mode", true);

            return ToolResult.success("Permission mode set to plan");
        }

        @Override
        public boolean isReadOnly(Void args) {
            return false;
        }
    }

    /**
     * Switch settings permission mode back to default.
     * Python equivalent: exit_plan_mode_tool.py
     */
    public static class ExitPlanModeTool extends BaseTool<Void> {
        public ExitPlanModeTool() {
            super("exit_plan_mode",
                    "Switch permission mode back to default.",
                    Void.class);
        }

        @Override
        public ToolResult execute(Void args, ToolExecutionContext ctx) {
            // 1. Persist to settings.json (matches Python: load → set mode → save)
            Settings settings = Settings.load();
            settings.permission().setMode(PermissionMode.DEFAULT.value());
            settings.save();

            // 2. Update runtime metadata for immediate engine awareness
            ctx.metadata().put("plan_mode", false);
            ctx.metadata().put("plan_ready", true);

            return ToolResult.success("Permission mode set to default");
        }

        @Override
        public boolean isReadOnly(Void args) {
            return false;
        }
    }
}
