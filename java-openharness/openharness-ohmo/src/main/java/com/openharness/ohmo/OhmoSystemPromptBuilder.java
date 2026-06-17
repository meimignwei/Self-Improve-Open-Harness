package com.openharness.ohmo;

import com.openharness.config.MemorySettings;
import com.openharness.extensions.memory.MemoryEntry;
import com.openharness.extensions.memory.MemoryManager;
import com.openharness.extensions.prompts.EnvironmentInfoBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the ohmo personality prompt: Base + Soul + Identity + User Profile + Memory.
 * Java equivalent of Python ohmo/prompts.py.
 */
public class OhmoSystemPromptBuilder {

    private static final String BASE_PROMPT = """
            You are ohmo, a personal AI agent built on OpenHarness.
            You have access to tools, skills, and persistent memory.
            Be helpful, concise, and proactive.
            """;

    public String build(Path cwd, Path workspace) {
        return build(cwd, workspace, null);
    }

    /**
     * Build the full system prompt with semantic memory injection.
     *
     * @param cwd           current working directory
     * @param workspace     ohmo workspace root
     * @param userQuery     latest user prompt for relevance-based memory search (nullable)
     */
    public String build(Path cwd, Path workspace, String userQuery) {
        List<String> sections = new ArrayList<>();
        sections.add(BASE_PROMPT);

        appendSection(sections, workspace.resolve("soul.md"), "# ohmo Soul");
        appendSection(sections, workspace.resolve("identity.md"), "# ohmo Identity");
        appendSection(sections, workspace.resolve("user.md"), "# User Profile");

        String bootstrap = readText(workspace.resolve("BOOTSTRAP.md"));
        if (bootstrap != null && !bootstrap.isEmpty()) {
            sections.add("# First-Run Bootstrap\n" + bootstrap);
        }

        // Environment info (OS, shell, Java, git, date)
        sections.add(EnvironmentInfoBuilder.build());

        sections.add("""
                # ohmo Workspace
                - Personal workspace root: %s
                - Resume only within ohmo sessions; do not assume interoperability with plain OpenHarness sessions.
                """.formatted(workspace));

        // Semantic memory injection — search by relevance, not dump all files
        MemorySettings memSettings = new MemorySettings();
        String memoryPrompt = buildMemoryPrompt(workspace, userQuery, memSettings);
        if (memoryPrompt != null && !memoryPrompt.isEmpty()) {
            sections.add(memoryPrompt);
        }

        return String.join("\n\n", sections);
    }

    // ------------------------------------------------------------------
    // Memory injection — semantic relevance search
    // ------------------------------------------------------------------

    /**
     * Search relevant memories using MemoryManager semantic search,
     * then format the top matches into the prompt.
     */
    private String buildMemoryPrompt(Path workspace, String userQuery,
                                       MemorySettings memSettings) {
        Path memoryDir = workspace.resolve("memory");
        if (!Files.exists(memoryDir)) return null;

        MemoryManager mgr = new MemoryManager(memoryDir, memSettings);
        int maxBytes = memSettings.maxEntrypointBytes() > 0
                ? memSettings.maxEntrypointBytes() : 25_000;
        int maxLines = memSettings.maxEntrypointLines() > 0
                ? memSettings.maxEntrypointLines() : 200;

        List<MemoryEntry> all = mgr.listAll();
        if (all.isEmpty()) return null;

        // If there's a user query, inject top-K relevant memories
        if (userQuery != null && !userQuery.isBlank()) {
            List<MemoryEntry.ScoredMemory> results = mgr.search(userQuery, 8);
            if (results.isEmpty()) return null;

            StringBuilder sb = new StringBuilder("# Relevant Memories\n\n");
            for (MemoryEntry.ScoredMemory scored : results) {
                if (scored.score() < 0.1) continue;
                MemoryEntry.MemoryHeader h = scored.memory().header();
                sb.append("<memory type=\"").append(h.type().name().toLowerCase())
                        .append("\" name=\"").append(h.name())
                        .append("\" relevance=\"").append(String.format("%.2f", scored.score()))
                        .append("\">\n");
                String body = scored.memory().body();
                if (body != null) {
                    // Apply entrypoint limits
                    String[] bodyLines = body.split("\n", -1);
                    if (bodyLines.length > maxLines) {
                        body = String.join("\n",
                                java.util.Arrays.copyOf(bodyLines, maxLines)) + "\n...";
                    }
                    if (body.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                        body = new String(body.getBytes(StandardCharsets.UTF_8), 0,
                                maxBytes, StandardCharsets.UTF_8);
                    }
                }
                sb.append(body);
                sb.append("\n</memory>\n\n");
            }
            return sb.toString();
        }

        // Fallback: inject most important memories (importance >= 7), max 5
        List<MemoryEntry> important = all.stream()
                .filter(m -> m.header().importance() >= 7)
                .sorted((a, b) -> Integer.compare(b.header().importance(), a.header().importance()))
                .limit(5)
                .toList();

        if (important.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("# Key Memories\n\n");
        for (MemoryEntry m : important) {
            sb.append("## ").append(m.header().name()).append("\n")
                    .append(m.body()).append("\n\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void appendSection(List<String> sections, Path file, String heading) {
        String content = readText(file);
        if (content != null && !content.isEmpty()) {
            sections.add(heading + "\n" + content);
        }
    }

    private String readText(Path file) {
        if (!Files.exists(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
