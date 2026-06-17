package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Shorten a piece of text for compact display.
 */
public class BriefTool extends BaseTool<BriefTool.Input> {

    public BriefTool() {
        super("brief", "Shorten a piece of text for compact display.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        String text = arguments.text().strip();
        int maxChars = arguments.maxChars();
        if (text.length() <= maxChars) {
            return ToolResult.success(text);
        }
        return ToolResult.success(text.substring(0, maxChars).stripTrailing() + "...");
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    public record Input(String text, int maxChars) {
        public Input {
            if (text == null) text = "";
            if (maxChars < 20) maxChars = 20;
            if (maxChars > 2000) maxChars = 2000;
        }

        public Input(String text) {
            this(text, 200);
        }
    }
}
