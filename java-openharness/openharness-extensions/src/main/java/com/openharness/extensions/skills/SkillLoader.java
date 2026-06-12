package com.openharness.extensions.skills;

import com.openharness.config.Settings;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Loads skills from bundled, user, project, and plugin directories.
 * Java equivalent of Python's SkillLoader.
 */
public class SkillLoader {

    private static final Logger LOG = Logger.getLogger(SkillLoader.class.getName());
    private static final Yaml YAML = new Yaml();

    /**
     * Load all skills into the given registry.
     */
    public SkillRegistry loadAll(Settings settings, Path cwd) {
        SkillRegistry registry = new SkillRegistry();

        // 1. Bundled skills (jar resources/skills/)
        loadFromResourceDir(registry, "skills/", "bundled");

        // 2. User skills (~/.openharness/skills/)
        loadFromDir(registry, com.openharness.config.Paths.homeSkillsDir(), "user");

        // 3. Project skills (cwd → git root .openharness/skills/)
        Path projectRoot = findProjectRoot(cwd);
        if (projectRoot != null) {
            for (String skillDir : settings.projectSkillDirs()) {
                loadFromDir(registry, projectRoot.resolve(skillDir), "project");
            }
        }

        return registry;
    }

    /**
     * Load all SKILL.md files from a directory.
     */
    void loadFromDir(SkillRegistry registry, Path dir, String source) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir, 3)) {
            files.filter(f -> f.getFileName().toString().equals("SKILL.md"))
                    .forEach(skillMd -> {
                        try {
                            SkillDefinition skill = parseSkillMd(skillMd, dir, source);
                            registry.register(skill);
                        } catch (IOException e) {
                            LOG.warning("Failed to parse skill: " + skillMd + " - " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOG.warning("Failed to walk skill directory: " + dir + " - " + e.getMessage());
        }
    }

    /**
     * Load skills from a classpath resource directory.
     */
    void loadFromResourceDir(SkillRegistry registry, String resourcePath, String source) {
        // Bundled skills are loaded from the JAR classpath.
        // For now, this is a no-op — bundled skills will be added when resources are present.
        LOG.fine("Loading bundled skills from resources/" + resourcePath);
    }

    /**
     * Parse a SKILL.md file with YAML frontmatter (--- delimited).
     */
    SkillDefinition parseSkillMd(Path skillMd, Path baseDir, String source) throws IOException {
        String content = Files.readString(skillMd);

        if (content.startsWith("---")) {
            int endIdx = content.indexOf("---", 3);
            if (endIdx > 0) {
                String yamlPart = content.substring(3, endIdx);
                String body = content.substring(endIdx + 3).stripLeading();

                @SuppressWarnings("unchecked")
                Map<String, Object> fm = YAML.load(yamlPart);

                return new SkillDefinition(
                        (String) fm.getOrDefault("name", baseDir.getFileName().toString()),
                        (String) fm.getOrDefault("description", ""),
                        body,
                        source,
                        skillMd,
                        baseDir,
                        (String) fm.get("command_name"),
                        (String) fm.get("display_name"),
                        parseStringList(fm.get("aliases")),
                        fm.getOrDefault("user_invocable", true) instanceof Boolean b ? b : true,
                        fm.getOrDefault("disable_model_invocation", false) instanceof Boolean b ? b : false,
                        (String) fm.get("model"),
                        (String) fm.get("argument_hint")
                );
            }
        }

        // Fallback: no frontmatter — use first # heading as name
        String name = baseDir.getFileName().toString();
        String description = "";
        for (String line : content.lines().toList()) {
            if (line.startsWith("# ") && name.equals(baseDir.getFileName().toString())) {
                name = line.substring(2).strip();
            }
        }
        String firstParagraph = content.lines()
                .dropWhile(l -> l.startsWith("#"))
                .filter(l -> !l.isBlank())
                .findFirst().orElse("");
        if (!firstParagraph.isEmpty()) description = firstParagraph;

        return new SkillDefinition(name, description, content, source,
                skillMd, baseDir, null, null, List.of(), true, false, null, null);
    }

    /**
     * Find the project root by walking up until a .git directory is found.
     */
    static Path findProjectRoot(Path cwd) {
        Path current = cwd.toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return cwd; // fallback to cwd
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) result.add(s);
            }
            return result;
        }
        return List.of();
    }
}
