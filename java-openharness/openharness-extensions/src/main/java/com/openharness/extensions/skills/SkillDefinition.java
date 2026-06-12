package com.openharness.extensions.skills;

import java.nio.file.Path;
import java.util.List;

/**
 * Skill definition parsed from a SKILL.md file.
 * Java equivalent of Python's SkillDefinition frozen dataclass.
 */
public record SkillDefinition(
        String name,
        String description,
        String content,
        String source,
        Path path,
        Path baseDir,
        String commandName,
        String displayName,
        List<String> aliases,
        boolean userInvocable,
        boolean disableModelInvocation,
        String model,
        String argumentHint) {

    public SkillDefinition {
        if (aliases == null) aliases = List.of();
        if (commandName == null && name != null) commandName = name;
        if (displayName == null) displayName = name;
    }
}
