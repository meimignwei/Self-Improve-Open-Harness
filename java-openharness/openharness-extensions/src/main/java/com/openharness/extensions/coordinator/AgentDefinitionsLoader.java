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

    /**
     * Look up a single agent definition by name.
     */
    public AgentDefinition getDefinition(String name) {
        return loadAll(List.of()).stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElse(null);
    }

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
        return switch (name) {
            case "general-purpose" -> new AgentDefinition(
                    "general-purpose", "General-purpose agent for research and implementation tasks",
                    "You are a capable software engineering agent. Complete the assigned task thoroughly.",
                    List.of("bash", "file_read", "file_edit", "file_write", "glob", "grep",
                            "web_fetch", "web_search", "task_create", "task_get", "task_list",
                            "task_output", "skill"),
                    List.of(), null, null, null, null,
                    List.of(), List.of(), List.of(), null, false, null, null, null,
                    List.of(), false, null, "general-purpose", "builtin", "general-purpose.md", null);

            case "Explore" -> new AgentDefinition(
                    "Explore", "Fast agent specialized for exploring codebases",
                    "You are a code exploration agent. Find files, search code, and answer questions about the codebase. Do not modify files.",
                    List.of("glob", "grep", "file_read", "web_fetch", "web_search"),
                    List.of("file_edit", "file_write", "bash"),
                    null, null, null, null,
                    List.of(), List.of(), List.of(), null, false, null, null, null,
                    List.of(), false, null, "Explore", "builtin", "Explore.md", null);

            case "Plan" -> new AgentDefinition(
                    "Plan", "Software architect agent for designing implementation plans",
                    "You are a software architect. Design implementation plans, identify critical files, and consider architectural trade-offs. Do not modify files.",
                    List.of("glob", "grep", "file_read", "web_fetch", "web_search",
                            "enter_plan_mode", "exit_plan_mode"),
                    List.of("file_edit", "file_write", "bash"),
                    null, null, null, null,
                    List.of(), List.of(), List.of(), null, false, null, null, null,
                    List.of(), false, null, "Plan", "builtin", "Plan.md", null);

            case "worker" -> new AgentDefinition(
                    "worker", "Worker agent that executes autonomous tasks delegated by the coordinator",
                    null,
                    List.of("bash", "file_read", "file_edit", "file_write", "glob", "grep",
                            "web_fetch", "web_search", "task_create", "task_get", "task_list",
                            "task_output", "skill"),
                    List.of(), null, null, null, 200,
                    List.of(), List.of(), List.of(), null, true, null, null, null,
                    List.of(), false, null, "worker", "builtin", "worker.md", null);

            case "verification" -> new AgentDefinition(
                    "verification", "Verification agent that tests and validates code changes",
                    "You are a verification agent. Prove code works by running tests, checking types, and trying edge cases. Be skeptical - if something looks off, dig in. Never rubber-stamp.",
                    List.of("bash", "file_read", "glob", "grep"),
                    List.of("file_edit", "file_write"),
                    null, null, null, null,
                    List.of(), List.of(), List.of(), null, false, null, null, null,
                    List.of(), false, null, "verification", "builtin", "verification.md", null);

            case "statusline-setup" -> new AgentDefinition(
                    "statusline-setup", "Configure the user's Claude Code status line setting",
                    "You configure the Claude Code status line. Read and edit the status line configuration.",
                    List.of("file_read", "file_edit"), List.of(),
                    null, null, null, null,
                    List.of(), List.of(), List.of(), null, false, null, null, null,
                    List.of(), false, null, "statusline-setup", "builtin", "statusline-setup.md", null);

            case "claude-code-guide" -> new AgentDefinition(
                    "claude-code-guide", "Answer questions about Claude Code CLI features and usage",
                    "You answer questions about Claude Code features, hooks, slash commands, MCP servers, settings, and IDE integrations.",
                    List.of("web_fetch", "web_search", "file_read", "glob", "grep"),
                    List.of("file_edit", "file_write", "bash"),
                    null, null, null, null,
                    List.of(), List.of(), List.of(), null, false, null, null, null,
                    List.of(), false, null, "claude-code-guide", "builtin", "claude-code-guide.md", null);

            default -> null;
        };
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
                parsePermissions(fm),
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

    private static List<String> parsePermissions(Map<String, Object> fm) {
        Object raw = fm.get("permissions");
        if (raw == null) return List.of();
        String rawStr = raw.toString().trim();
        if (rawStr.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : rawStr.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
