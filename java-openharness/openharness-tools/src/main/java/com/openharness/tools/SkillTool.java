package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.config.Settings;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.extensions.skills.SkillDefinition;
import com.openharness.extensions.skills.SkillLoader;
import com.openharness.extensions.skills.SkillRegistry;

/**
 * Return the content of a loaded skill (read-only content lookup).
 * Java equivalent of Python's skill_tool.
 * <p>
 * Does NOT execute skills — only returns the skill's content text.
 * Three-attempt name matching: exact match → lowercase → capitalize first letter.
 */
public class SkillTool extends BaseTool<SkillTool.Input> {

    public SkillTool() {
        super("skill",
                "Read a bundled, user, project, or plugin skill by name.",
                Input.class);
    }

    @Override
    public ToolResult execute(Input args, ToolExecutionContext ctx) {
        // Load the skill registry (bundled + user + project skills)
        Settings settings = Settings.load();
        SkillLoader loader = new SkillLoader();
        SkillRegistry registry = loader.loadAll(settings, ctx.cwd());

        String name = args.skill();
        // Three-attempt name matching, matching Python's logic:
        // 1. exact match
        // 2. lowercase match
        // 3. capitalize first letter match
        SkillDefinition skill = registry.get(name).orElse(null);
        if (skill == null) {
            skill = registry.get(name.toLowerCase()).orElse(null);
        }
        if (skill == null) {
            String capitalized = name.isEmpty()
                    ? name
                    : Character.toUpperCase(name.charAt(0)) + name.substring(1);
            skill = registry.get(capitalized).orElse(null);
        }

        if (skill == null) {
            return ToolResult.error("Skill not found: " + name);
        }

        if (skill.disableModelInvocation()) {
            String commandName = skill.commandName() != null ? skill.commandName() : skill.name();
            return ToolResult.error(
                    "Skill " + commandName + " can only be invoked by the user as /" + commandName + ".");
        }

        return ToolResult.success(skill.content());
    }

    @Override
    public boolean isReadOnly(Input args) {
        return true;
    }

    /**
     * Input: a single skill name to look up.
     * Python equivalent: SkillToolInput — single 'name: str' field.
     * Parameter kept as 'skill' for API consistency (Python uses 'name').
     */
    public record Input(String skill) {
        public Input {
            if (skill == null) throw new IllegalArgumentException("skill is required");
        }
    }
}
