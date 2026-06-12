package com.openharness.extensions.prompts;

import com.openharness.config.Settings;
import com.openharness.extensions.skills.SkillRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * Assembles the full system prompt from base, environment, skills, memory, and rules.
 * Java equivalent of Python's build_system_prompt().
 */
public class SystemPromptBuilder {

    private static final String BASE_SYSTEM_PROMPT = """
            You are an interactive AI coding assistant.

            You have access to a set of tools to help users with software engineering tasks.
            Use the instructions below and the tools available to assist the user.

            IMPORTANT: Assist with authorized security testing, defensive security,
            CTF challenges, and educational contexts. Refuse destructive requests.

            IMPORTANT: You must NEVER generate or guess URLs for the user unless you
            are confident that they are for programming purposes.

            ## Doing tasks
            - The user will primarily request software engineering tasks.
            - Prefer editing existing files over creating new ones.
            - Write safe, secure, and correct code.
            - Don't add features or abstractions beyond what the task requires.

            ## Tone and style
            - Your responses should be short and concise.
            - Default to writing no comments in code.
            """;

    public String build(Settings settings, PromptContext ctx) {
        StringBuilder sb = new StringBuilder();

        // 1. Base prompt
        sb.append(BASE_SYSTEM_PROMPT);
        if (settings.systemPrompt() != null && !settings.systemPrompt().isBlank()) {
            sb.append("\n").append(settings.systemPrompt());
        }

        // 2. Environment section
        sb.append(EnvironmentInfoBuilder.build());

        // 3. Permission mode
        sb.append("\n").append(buildPermissionSection(settings));

        // 4. Fast mode
        if (ctx.fastMode()) {
            sb.append("\n**Fast mode is enabled.**\n");
        }

        // 5. Skills section
        sb.append(buildSkillsSection(ctx.skillRegistry()));

        // 6. Auto mode
        sb.append(buildAutoSection(settings));

        return sb.toString();
    }

    /**
     * Build the short-form system prompt (used when full prompt is too expensive).
     */
    public String buildShort(Settings settings, PromptContext ctx) {
        return BASE_SYSTEM_PROMPT + EnvironmentInfoBuilder.build();
    }

    private String buildPermissionSection(Settings settings) {
        return switch (settings.permission().mode()) {
            case "default" -> """
                    ## Permissions
                    - You are in DEFAULT permission mode.
                    - Read-only tools run automatically.
                    - Mutating tools require user confirmation.
                    """;
            case "plan" -> """
                    ## Permissions
                    - You are in PLAN mode.
                    - You can read files and search code.
                    - You CANNOT modify any files or run commands.
                    - When ready, present your plan for user approval.
                    """;
            case "full_auto" -> """
                    ## Permissions
                    - You are in FULL_AUTO mode. All tools run without confirmation.
                    """;
            default -> "";
        };
    }

    private String buildSkillsSection(SkillRegistry skillRegistry) {
        if (skillRegistry == null || skillRegistry.listSkills().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n## Available Skills\n\n");
        for (var skill : skillRegistry.listSkills()) {
            if (skill.disableModelInvocation()) continue;
            sb.append("- ").append(skill.name());
            if (!skill.description().isBlank()) {
                sb.append(": ").append(skill.description().strip().replaceAll("\s+", " "));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildAutoSection(Settings settings) {
        if ("full_auto".equals(settings.permission().mode())) {
            return "\n## Auto Memory\n\nYou have a persistent memory system.\n";
        }
        return "";
    }

    public record PromptContext(
            Path cwd,
            boolean fastMode,
            SkillRegistry skillRegistry,
            List<String> relevantMemories,
            boolean includeProjectMemory) {

        public static PromptContext from(Path cwd) {
            return new PromptContext(cwd, false, null, List.of(), true);
        }
    }
}
