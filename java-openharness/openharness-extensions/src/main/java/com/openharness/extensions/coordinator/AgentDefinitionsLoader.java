package com.openharness.extensions.coordinator;

import com.openharness.config.Paths;
import com.openharness.extensions.plugins.PluginManifest;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Three-tier agent definition loading: builtin < user < plugin.
 * Java equivalent of Python coordinator/agent_definitions.py.
 */
public class AgentDefinitionsLoader {

    private static final List<String> BUILTIN_AGENTS = List.of(
            "general-purpose", "statusline-setup", "claude-code-guide",
            "Explore", "Plan", "worker", "verification"
    );

    public List<AgentDefinition> loadAll(List<PluginManifest> plugins) {
        Map<String, AgentDefinition> agents = new LinkedHashMap<>();

        for (String name : BUILTIN_AGENTS) {
            AgentDefinition def = loadBuiltinAgent(name);
            if (def != null) {
                agents.put(def.name(), def);
            }
        }

        loadFromDir(agents, Paths.homeAgentsDir());

        for (PluginManifest plugin : plugins) {
            Path agentsDir = plugin.pluginDir() != null
                    ? plugin.pluginDir().resolve("agents")
                    : null;
            if (agentsDir != null && Files.exists(agentsDir)) {
                loadFromDir(agents, agentsDir);
            }
        }

        return List.copyOf(agents.values());
    }

    private AgentDefinition loadBuiltinAgent(String name) {
        return null;
    }

    void loadFromDir(Map<String, AgentDefinition> agents, Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .forEach(f -> {
                        try {
                            AgentDefinition def = parseAgentMd(f);
                            agents.put(def.name(), def);
                        } catch (Exception e) {
                            System.err.println("Failed to parse agent: " + f);
                        }
                    });
        } catch (IOException ignored) {}
    }

    AgentDefinition parseAgentMd(Path mdFile) throws IOException {
        String content = Files.readString(mdFile, StandardCharsets.UTF_8);
        Map<String, Object> fm = parseFrontmatter(content);
        String body = bodyContent(content);
        return buildFromFrontmatter(fm, body, mdFile);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parseFrontmatter(String content) {
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                Yaml yaml = new Yaml();
                Map<String, Object> result = yaml.load(new StringReader(content.substring(3, end).trim()));
                return result != null ? result : Map.of();
            }
        }
        return Map.of();
    }

    String bodyContent(String content) {
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) return content.substring(end + 3).trim();
        }
        return content.trim();
    }

    @SuppressWarnings("unchecked")
    AgentDefinition buildFromFrontmatter(Map<String, Object> fm, String body, Path mdFile) {
        String name = getString(fm, "name", mdFile.getFileName().toString().replace(".md", ""));
        return new AgentDefinition(
                name,
                getString(fm, "description", ""),
                getString(fm, "system_prompt", body),
                (List<String>) fm.getOrDefault("tools", List.of()),
                (List<String>) fm.getOrDefault("disallowed_tools", List.of()),
                getString(fm, "model", null),
                getString(fm, "effort", null),
                null,
                getInt(fm, "max_turns", null),
                (List<String>) fm.getOrDefault("skills", List.of()),
                (List<String>) fm.getOrDefault("mcp_servers", List.of()),
                (List<String>) fm.getOrDefault("required_mcp_servers", List.of()),
                getString(fm, "color", null),
                getBool(fm, "background", false),
                getString(fm, "initial_prompt", null),
                getString(fm, "memory", null),
                getString(fm, "isolation", null),
                getBool(fm, "omit_claude_md", false),
                getString(fm, "critical_system_reminder", null),
                getString(fm, "subagent_type", null),
                getString(fm, "source", "user"),
                mdFile.getFileName().toString(),
                mdFile.getParent()
        );
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v != null ? v.toString() : def;
    }

    private static Integer getInt(Map<String, Object> map, String key, Integer def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }
}
