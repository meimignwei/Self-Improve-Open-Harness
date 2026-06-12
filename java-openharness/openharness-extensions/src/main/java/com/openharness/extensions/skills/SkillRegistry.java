package com.openharness.extensions.skills;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-key index registry for skills.
 * Indexed by name, command name, and aliases.
 * Java equivalent of Python's SkillRegistry.
 */
public class SkillRegistry {

    private final Map<String, SkillDefinition> byName = new ConcurrentHashMap<>();
    private final Map<String, SkillDefinition> byCommand = new ConcurrentHashMap<>();
    private final Map<String, SkillDefinition> byAlias = new ConcurrentHashMap<>();

    public void register(SkillDefinition skill) {
        byName.put(skill.name(), skill);
        if (skill.commandName() != null) {
            byCommand.put(skill.commandName(), skill);
        }
        for (String alias : skill.aliases()) {
            byAlias.put(alias, skill);
        }
    }

    public void unregister(String name) {
        SkillDefinition skill = byName.remove(name);
        if (skill != null) {
            if (skill.commandName() != null) byCommand.remove(skill.commandName());
            skill.aliases().forEach(byAlias::remove);
        }
    }

    public Optional<SkillDefinition> get(String key) {
        SkillDefinition byNameResult = byName.get(key);
        if (byNameResult != null) return Optional.of(byNameResult);
        SkillDefinition byCmdResult = byCommand.get(key);
        if (byCmdResult != null) return Optional.of(byCmdResult);
        SkillDefinition byAliasResult = byAlias.get(key);
        if (byAliasResult != null) return Optional.of(byAliasResult);
        return Optional.empty();
    }

    public List<SkillDefinition> listSkills() {
        return List.copyOf(byName.values());
    }

    public int size() { return byName.size(); }
}
