package com.openharness.extensions.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that bundled skills are loaded from classpath resources.
 */
class SkillLoaderBundledTest {

    @Test
    void shouldLoadBundledSkills() {
        SkillLoader loader = new SkillLoader();
        SkillRegistry registry = new SkillRegistry();
        loader.loadFromResourceDir(registry, "skills/", "bundled");

        assertTrue(registry.size() >= 8,
                "Expected at least 8 bundled skills, found: " + registry.size());

        assertTrue(registry.get("plan").isPresent(), "plan skill should be loaded");
        assertTrue(registry.get("test").isPresent(), "test skill should be loaded");
        assertTrue(registry.get("review").isPresent(), "review skill should be loaded");
        assertTrue(registry.get("simplify").isPresent(), "simplify skill should be loaded");
        assertTrue(registry.get("debug").isPresent(), "debug skill should be loaded");
        assertTrue(registry.get("diagnose").isPresent(), "diagnose skill should be loaded");
        assertTrue(registry.get("commit").isPresent(), "commit skill should be loaded");
        assertTrue(registry.get("skill-creator").isPresent(), "skill-creator skill should be loaded");
    }

    @Test
    void bundledSkillsShouldHaveDescriptions() {
        SkillLoader loader = new SkillLoader();
        SkillRegistry registry = new SkillRegistry();
        loader.loadFromResourceDir(registry, "skills/", "bundled");

        for (SkillDefinition skill : registry.listSkills()) {
            assertNotNull(skill.description(), "Skill " + skill.name() + " should have a description");
            assertFalse(skill.description().isBlank(), "Skill " + skill.name() + " description should not be blank");
            assertEquals("bundled", skill.source());
        }
    }

    @Test
    void bundledSkillsShouldHaveBodyContent() {
        SkillLoader loader = new SkillLoader();
        SkillRegistry registry = new SkillRegistry();
        loader.loadFromResourceDir(registry, "skills/", "bundled");

        var plan = registry.get("plan").orElseThrow();
        assertTrue(plan.content().contains("Workflow"), "plan skill should contain Workflow section");
    }
}
