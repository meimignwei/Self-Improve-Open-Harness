package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Truncates text to a maximum character count.
 */
public class BriefTool extends BaseTool<BriefTool.Input> {

    public BriefTool() {
        super("brief", "Truncates text to a maximum character count, appending '...' if truncated.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        String text = arguments.text();
        int maxChars = arguments.maxChars();
        if (text.length() <= maxChars) {
            return ToolResult.success(text);
        }
        return ToolResult.success(text.substring(0, maxChars) + "...");
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
    }
}
