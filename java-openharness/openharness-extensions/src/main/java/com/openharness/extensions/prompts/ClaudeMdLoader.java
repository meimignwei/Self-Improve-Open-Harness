package com.openharness.extensions.prompts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Discovers and loads CLAUDE.md files from cwd up to the git root.
 * Also loads .claude/CLAUDE.md and .claude/rules/*.md.
 * Java equivalent of Python's claudemd.py.
 */
public final class ClaudeMdLoader {

    private static final Logger LOG = Logger.getLogger(ClaudeMdLoader.class.getName());

    private ClaudeMdLoader() {}

    /**
     * Load all CLAUDE.md content from cwd to project root.
     */
    public static String load(Path cwd) {
        StringBuilder sb = new StringBuilder();
        List<Path> files = discover(cwd);

        if (!files.isEmpty()) {
            sb.append("\n# Project Instructions (CLAUDE.md)\n\n");
            for (Path f : files) {
                try {
                    sb.append("<!-- from ").append(f.getParent()).append(" -->\n");
                    sb.append(Files.readString(f)).append("\n\n");
                } catch (IOException e) {
                    LOG.fine("Failed to read CLAUDE.md: " + f);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Discover CLAUDE.md files from cwd up to git root.
     */
    private static List<Path> discover(Path start) {
        List<Path> found = new ArrayList<>();
        Path current = start.toAbsolutePath();
        Path gitRoot = findGitRoot(current);

        while (current != null) {
            // Direct CLAUDE.md
            Path claudeMd = current.resolve("CLAUDE.md");
            if (Files.isRegularFile(claudeMd)) found.add(claudeMd);

            // .claude/CLAUDE.md
            Path claudeDirMd = current.resolve(".claude/CLAUDE.md");
            if (Files.isRegularFile(claudeDirMd)) found.add(claudeDirMd);

            // .claude/rules/*.md
            Path rulesDir = current.resolve(".claude/rules");
            if (Files.isDirectory(rulesDir)) {
                try (Stream<Path> files = Files.list(rulesDir)) {
                    files.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().endsWith(".md"))
                            .sorted()
                            .forEach(found::add);
                } catch (IOException e) {
                    // ignore
                }
            }

            if (current.equals(gitRoot)) break;
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }

        // Reverse so closest to cwd comes first
        java.util.Collections.reverse(found);
        return found;
    }

    static Path findGitRoot(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }
        return start;
    }
}
