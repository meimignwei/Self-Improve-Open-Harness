package com.openharness.extensions.prompts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Discovers and loads CLAUDE.md files from cwd up to the filesystem root.
 * Also loads .claude/CLAUDE.md and .claude/rules/*.md.
 * Java equivalent of Python's claudemd.py discover_claude_md_files() + load_claude_md_prompt().
 */
public final class ClaudeMdLoader {

    private static final Logger LOG = Logger.getLogger(ClaudeMdLoader.class.getName());
    private static final int MAX_CHARS_PER_FILE = 12_000;

    private ClaudeMdLoader() {}

    /**
     * Load all CLAUDE.md content from cwd to filesystem root.
     * Returns null if no files found (matching Python's return None).
     */
    public static String load(Path cwd) {
        List<Path> files = discover(cwd);

        if (files.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Project Instructions");

        for (Path f : files) {
            try {
                String content = Files.readString(f).strip();
                if (content.isBlank()) continue;

                if (content.length() > MAX_CHARS_PER_FILE) {
                    content = content.substring(0, MAX_CHARS_PER_FILE) + "\n...[truncated]...";
                }

                sb.append("\n\n## ").append(f).append("\n");
                sb.append("```md\n");
                sb.append(content);
                sb.append("\n```");
            } catch (IOException e) {
                LOG.fine("Failed to read CLAUDE.md: " + f);
            }
        }
        return sb.toString();
    }

    /**
     * Discover CLAUDE.md files from cwd up to the filesystem root.
     * Matching Python's discover_claude_md_files() which traverses all parents.
     */
    private static List<Path> discover(Path start) {
        List<Path> found = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        Path current = start.toAbsolutePath();

        while (current != null) {
            // Direct CLAUDE.md
            Path claudeMd = current.resolve("CLAUDE.md");
            if (Files.isRegularFile(claudeMd) && seen.add(claudeMd)) {
                found.add(claudeMd);
            }

            // .claude/CLAUDE.md
            Path claudeDirMd = current.resolve(".claude/CLAUDE.md");
            if (Files.isRegularFile(claudeDirMd) && seen.add(claudeDirMd)) {
                found.add(claudeDirMd);
            }

            // .claude/rules/*.md
            Path rulesDir = current.resolve(".claude/rules");
            if (Files.isDirectory(rulesDir)) {
                try (Stream<Path> files = Files.list(rulesDir)) {
                    files.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().endsWith(".md"))
                            .sorted()
                            .forEach(f -> {
                                if (seen.add(f)) found.add(f);
                            });
                } catch (IOException e) {
                    // ignore
                }
            }

            // Traverse ALL parents until filesystem root (matching Python)
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }

        return found;
    }
}
