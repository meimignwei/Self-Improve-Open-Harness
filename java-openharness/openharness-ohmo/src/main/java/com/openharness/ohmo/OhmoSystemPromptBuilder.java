package com.openharness.ohmo;

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
        List<String> sections = new ArrayList<>();
        sections.add(BASE_PROMPT);

        appendSection(sections, workspace.resolve("soul.md"), "# ohmo Soul");
        appendSection(sections, workspace.resolve("identity.md"), "# ohmo Identity");
        appendSection(sections, workspace.resolve("user.md"), "# User Profile");

        String bootstrap = readText(workspace.resolve("BOOTSTRAP.md"));
        if (bootstrap != null && !bootstrap.isEmpty()) {
            sections.add("# First-Run Bootstrap\n" + bootstrap);
        }

        sections.add("""
                # ohmo Workspace
                - Personal workspace root: %s
                - Resume only within ohmo sessions; do not assume interoperability with plain OpenHarness sessions.
                """.formatted(workspace));

        String memoryPrompt = loadOhmoMemoryPrompt(workspace);
        if (memoryPrompt != null && !memoryPrompt.isEmpty()) {
            sections.add(memoryPrompt);
        }

        return String.join("\n\n", sections);
    }

    private void appendSection(List<String> sections, Path file, String heading) {
        String content = readText(file);
        if (content != null && !content.isEmpty()) {
            sections.add(heading + "\n" + content);
        }
    }

    private String loadOhmoMemoryPrompt(Path workspace) {
        Path memoryDir = workspace.resolve("memory");
        if (!Files.exists(memoryDir)) return null;

        StringBuilder sb = new StringBuilder("# Personal Memory\n\n");
        try (var files = Files.list(memoryDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .forEach(f -> {
                        String content = readText(f);
                        if (content != null && !content.isEmpty()) {
                            String name = f.getFileName().toString().replace(".md", "");
                            sb.append("## ").append(name).append("\n").append(content).append("\n\n");
                        }
                    });
        } catch (IOException ignored) {}

        return sb.toString().length() > "# Personal Memory\n\n".length()
                ? sb.toString() : null;
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
