package com.openharness.tools;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * File pattern matching tool using glob patterns.
 * Java equivalent of Python's GlobTool.
 */
public class GlobTool extends BaseTool<GlobTool.Input> {

    private static final int DEFAULT_LIMIT = 200;
    private static final int RG_TIMEOUT_SECONDS = 30;

    public GlobTool() {
        super("glob", "List files matching a glob pattern.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path root = arguments.root() != null
                ? context.cwd().resolve(arguments.root()).normalize()
                : context.cwd();

        String pattern = arguments.pattern();

        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return ToolResult.success("(no matches)");
        }

        // Prefer ripgrep's file walker when available and pattern is recursive
        List<String> matches = globWithRg(root, pattern, arguments.limit());
        if (matches != null) {
            if (matches.isEmpty()) {
                return ToolResult.success("(no matches)");
            }
            return ToolResult.success(String.join("\n", matches));
        }

        // Fallback to Java Files.walk + PathMatcher
        matches = globWithJava(root, pattern, arguments.limit());
        if (matches.isEmpty()) {
            return ToolResult.success("(no matches)");
        }
        return ToolResult.success(String.join("\n", matches));
    }

    /**
     * Try ripgrep file walker. Returns null if rg is not available or pattern
     * is simple enough for the Java fallback.
     */
    private List<String> globWithRg(Path root, String pattern, int limit) {
        // Only use rg for recursive patterns (Python: "**" in pattern or "/" in pattern)
        if (!pattern.contains("**") && !pattern.contains("/")) {
            return null;
        }

        // Check if rg is available
        if (!isRgAvailable()) {
            return null;
        }

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("rg");
            cmd.add("--files");

            // Auto-detect whether to include hidden paths
            if (looksLikeGitRepo(root)) {
                cmd.add("--hidden");
            }

            cmd.add("--glob");
            cmd.add(pattern);
            cmd.add(".");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            List<String> lines = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && lines.size() < limit) {
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty()) {
                        lines.add(trimmed);
                    }
                }
            }

            boolean finished = process.waitFor(RG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }

            // Sort for deterministic output (Python does this too)
            lines.sort(String::compareTo);
            return lines;

        } catch (Exception e) {
            return null; // Fall back to Java implementation
        }
    }

    /**
     * Check if ripgrep is installed and functional.
     */
    private static boolean isRgAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("rg", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Heuristic: determine whether we should include hidden paths when searching.
     * For codebases, hidden dirs like .github/ are relevant; for arbitrary dirs
     * (like a user's home), searching hidden paths can explode the search space.
     * Matches Python's {@code _looks_like_git_repo}.
     */
    private boolean looksLikeGitRepo(Path path) {
        Path current = path;
        for (int i = 0; i < 6; i++) {
            if (Files.exists(current.resolve(".git"))) {
                return true;
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) {
                break;
            }
            current = parent;
        }
        return false;
    }

    /**
     * Pure Java fallback using Files.walk and PathMatcher.
     */
    private List<String> globWithJava(Path root, String pattern, int limit) {
        String globPattern = "glob:" + pattern;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(globPattern);

        List<String> results = new ArrayList<>();
        try (Stream<Path> fileStream = Files.walk(root)) {
            fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(root.relativize(p)))
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                    .limit(limit)
                    .forEach(p -> results.add(root.relativize(p).toString()));
        } catch (IOException e) {
            // Return empty list on error
        }
        return results;
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    public record Input(
            @JsonAlias("path") String pattern,
            String root,
            int limit) {

        public Input {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("pattern is required");
            }
            if (limit <= 0) {
                limit = DEFAULT_LIMIT;
            } else if (limit > 5000) {
                limit = 5000;
            }
        }
    }
}
