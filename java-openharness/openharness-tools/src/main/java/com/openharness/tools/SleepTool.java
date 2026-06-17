package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Pause execution briefly.
 */
public class SleepTool extends BaseTool<SleepTool.Input> {

    public SleepTool() {
        super("sleep", "Sleep for a short duration.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        try {
            Thread.sleep((long) (arguments.seconds() * 1000));
            return ToolResult.success("Slept for " + arguments.seconds() + " seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Sleep interrupted: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    public record Input(double seconds) {
        public Input {
            if (seconds < 0) seconds = 0;
            if (seconds > 30) seconds = 30;
        }

        public Input() {
            this(1.0);
        }
    }
}
