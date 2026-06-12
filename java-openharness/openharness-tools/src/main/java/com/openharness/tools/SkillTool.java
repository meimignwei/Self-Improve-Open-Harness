package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Invokes a named skill from the loaded skill registry.
 * Java equivalent of Python's Skill tool.
 */
public class SkillTool extends BaseTool<SkillTool.Input> {

    private final SkillInvoker invoker;

    public SkillTool(SkillInvoker invoker) {
        super("skill", "Invoke a skill by name with optional arguments.", Input.class);
        this.invoker = invoker;
    }

    @Override
    public ToolResult execute(Input args, ToolExecutionContext ctx) {
        if (invoker == null) {
            return ToolResult.error("No skill invoker configured.");
        }
        try {
            String result = invoker.invoke(args.skill(), args.args());
            return ToolResult.success(result);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Skill not found: " + args.skill());
        } catch (Exception e) {
            return ToolResult.error("Skill execution failed: " + e.getMessage());
        }
    }

    @Override public boolean isReadOnly(Input args) { return false; }

    public record Input(String skill, String args) {
        public Input { if (skill == null) throw new IllegalArgumentException("skill is required"); }
    }

    @FunctionalInterface
    public interface SkillInvoker {
        String invoke(String skillName, String args);
    }
}
