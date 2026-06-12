package com.openharness.extensions.skills;

import com.openharness.config.Settings;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;
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
     * Handles both file-system (dev) and jar: (packaged) resources.
     */
    void loadFromResourceDir(SkillRegistry registry, String resourcePath, String source) {
        ClassLoader cl = getClass().getClassLoader();
        String bundledPath = resourcePath + "bundled/";
        URL dirUrl = cl.getResource(bundledPath);
        if (dirUrl == null) {
            LOG.fine("No bundled skills found at classpath:" + bundledPath);
            return;
        }

        List<String> mdFiles;
        try {
            if ("file".equals(dirUrl.getProtocol())) {
                mdFiles = listFilesInDir(Path.of(dirUrl.toURI()));
            } else if ("jar".equals(dirUrl.getProtocol())) {
                mdFiles = listFilesInJar(dirUrl, bundledPath);
            } else {
                LOG.warning("Unsupported protocol for bundled skills: " + dirUrl.getProtocol());
                return;
            }
        } catch (Exception e) {
            LOG.warning("Failed to enumerate bundled skills: " + e.getMessage());
            return;
        }

        for (String filename : mdFiles) {
            try (InputStream is = cl.getResourceAsStream(bundledPath + filename)) {
                if (is == null) continue;
                String content = new BufferedReader(new InputStreamReader(is))
                        .lines().collect(Collectors.joining("\n"));
                SkillDefinition skill = parseBundledSkill(content, filename, source);
                registry.register(skill);
                LOG.fine("Loaded bundled skill: " + skill.name());
            } catch (Exception e) {
                LOG.warning("Failed to load bundled skill " + filename + ": " + e.getMessage());
            }
        }
    }

    private List<String> listFilesInDir(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString())
                    .toList();
        }
    }

    private List<String> listFilesInJar(URL dirUrl, String bundledPath) throws IOException {
        List<String> result = new ArrayList<>();
        String prefix = bundledPath.endsWith("/") ? bundledPath : bundledPath + "/";
        try {
            JarURLConnection conn = (JarURLConnection) dirUrl.openConnection();
            try (JarFile jarFile = conn.getJarFile()) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith(prefix) && !entry.isDirectory() && name.endsWith(".md")) {
                        result.add(name.substring(prefix.length()));
                    }
                }
            }
        } catch (ClassCastException e) {
            // Fallback: try to parse jar:file:/path.jar!/path syntax manually
            String urlStr = dirUrl.toString();
            int jarSeparator = urlStr.indexOf("!/");
            if (jarSeparator > 0) {
                String jarPath = urlStr.substring(4, jarSeparator); // strip "jar:"
                try (JarFile jarFile = new JarFile(jarPath)) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(prefix) && !entry.isDirectory() && name.endsWith(".md")) {
                            result.add(name.substring(prefix.length()));
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Parse a bundled flat .md file (no enclosing directory).
     */
    SkillDefinition parseBundledSkill(String content, String filename, String source) {
        String baseName = filename.replaceAll("\\.md$", "");
        String name = baseName;
        String description = "";

        if (content.startsWith("---")) {
            int endIdx = content.indexOf("---", 3);
            if (endIdx > 0) {
                String yamlPart = content.substring(3, endIdx);
                String body = content.substring(endIdx + 3).stripLeading();

                @SuppressWarnings("unchecked")
                Map<String, Object> fm = YAML.load(yamlPart);
                name = (String) fm.getOrDefault("name", baseName);
                Object descObj = fm.get("description");
                if (descObj instanceof String s) {
                    description = s;
                } else if (descObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String) {
                    description = String.join(" ", (List<String>) list);
                }

                Path syntheticPath = Path.of("bundled", filename);
                return new SkillDefinition(
                        name,
                        description,
                        body,
                        source,
                        syntheticPath,
                        syntheticPath.getParent(),
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

        // Fallback: no frontmatter
        for (String line : content.lines().toList()) {
            if (line.startsWith("# ")) {
                name = line.substring(2).strip();
                break;
            }
        }
        String firstParagraph = content.lines()
                .dropWhile(l -> l.startsWith("#"))
                .filter(l -> !l.isBlank())
                .findFirst().orElse("");
        if (!firstParagraph.isEmpty()) description = firstParagraph;

        Path syntheticPath = Path.of("bundled", filename);
        return new SkillDefinition(name, description, content, source,
                syntheticPath, syntheticPath.getParent(), null, null, List.of(), true, false, null, null);
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
