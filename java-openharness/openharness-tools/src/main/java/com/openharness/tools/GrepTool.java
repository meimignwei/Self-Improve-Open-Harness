package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Content search tool with a pure Java fallback.
 * Java equivalent of Python's GrepTool.
 */
public class GrepTool extends BaseTool<GrepTool.Input> {

    private static final int DEFAULT_LIMIT = 200;

    public GrepTool() {
        super("grep", "Search file contents with a regular expression.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path root = arguments.root() != null
                ? resolvePath(context.cwd(), arguments.root())
                : context.cwd();

        if (!Files.exists(root)) {
            return ToolResult.error(
                    "Search root does not exist: " + root + "\n"
                            + "If you intended multiple roots, call grep separately for each root.");
        }

        // Single file mode
        if (Files.isRegularFile(root)) {
            Path displayBase = displayBase(root, context.cwd());
            List<String> matches = rgGrepFile(
                    root, arguments.pattern(), arguments.caseSensitive(),
                    arguments.limit(), displayBase, arguments.timeout());
            if (matches != null) {
                return formatRgResult(matches, arguments.timeout());
            }
            return fallbackGrepFiles(
                    List.of(root), arguments.pattern(), arguments.caseSensitive(),
                    arguments.limit(), displayBase);
        }

        // Directory mode: prefer ripgrep for performance
        List<String> matches = rgGrep(
                root, arguments.pattern(), arguments.file_glob(),
                arguments.caseSensitive(), arguments.limit(), arguments.timeout());
        if (matches != null) {
            return formatRgResult(matches, arguments.timeout());
        }

        // Pure Java fallback
        return fallbackGrepTree(
                root, arguments.pattern(), arguments.file_glob(),
                arguments.caseSensitive(), arguments.limit());
    }

    // ---- ripgrep helpers ----

    /**
     * Run rg on a directory. Returns null if rg is not available.
     * Matches Python's {@code _rg_grep}.
     */
    private List<String> rgGrep(Path root, String rgPattern, String fileGlob,
                                 boolean caseSensitive, int limit, int timeoutSeconds) {
        if (!isRgAvailable()) {
            return null;
        }

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("rg");
            cmd.add("--no-heading");
            cmd.add("--line-number");
            cmd.add("--color");
            cmd.add("never");

            // Check for .git or .gitignore in the root itself (matches Python)
            if (Files.exists(root.resolve(".git")) || Files.exists(root.resolve(".gitignore"))) {
                cmd.add("--hidden");
            }

            if (!caseSensitive) {
                cmd.add("-i");
            }

            if (fileGlob != null && !fileGlob.isEmpty()) {
                cmd.add("--glob");
                cmd.add(fileGlob);
            }

            // -- ensures patterns like -foo aren't parsed as flags
            cmd.add("--");
            cmd.add(rgPattern);
            cmd.add(".");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(false); // stderr discarded

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

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                lines.add(timeoutMarker(timeoutSeconds));
                process.destroyForcibly();
                return lines;
            }

            // rg exits 0 when matches found, 1 when none found.
            // Any other return code indicates error → fall back to Java.
            int exitCode = process.exitValue();
            if (exitCode == 0 || exitCode == 1 || exitCode == 143 /* SIGTERM */ || exitCode == 137 /* SIGKILL */) {
                if (lines.size() >= limit && process.isAlive()) {
                    process.destroyForcibly();
                }
                return lines;
            }
            return null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Run rg on a single file. Returns null if rg is not available.
     * Matches Python's {@code _rg_grep_file}.
     */
    private List<String> rgGrepFile(Path file, String rgPattern, boolean caseSensitive,
                                     int limit, Path displayBase, int timeoutSeconds) {
        if (!isRgAvailable()) {
            return null;
        }

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("rg");
            cmd.add("--no-heading");
            cmd.add("--line-number");
            cmd.add("--color");
            cmd.add("never");

            if (!caseSensitive) {
                cmd.add("-i");
            }

            cmd.add("--");
            cmd.add(rgPattern);
            cmd.add(file.getFileName().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(file.getParent().toFile());
            pb.redirectErrorStream(false);

            Process process = pb.start();
            List<String> lines = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && lines.size() < limit) {
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty()) {
                        // Prepend formatted path like Python: displayBase-relative path
                        lines.add(formatPath(file, displayBase) + ":" + trimmed);
                    }
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                lines.add(timeoutMarker(timeoutSeconds));
                process.destroyForcibly();
                return lines;
            }

            int exitCode = process.exitValue();
            if (exitCode == 0 || exitCode == 1 || exitCode == 143 || exitCode == 137) {
                return lines;
            }
            return null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Format rg results into a ToolResult, handling timeout markers.
     * Matches Python's {@code _format_rg_result}.
     */
    private ToolResult formatRgResult(List<String> matches, int timeoutSeconds) {
        String marker = timeoutMarker(timeoutSeconds);
        boolean timedOut = !matches.isEmpty() && matches.get(matches.size() - 1).equals(marker);
        List<String> rendered = timedOut ? matches.subList(0, matches.size() - 1) : matches;

        String output;
        if (rendered.isEmpty()) {
            output = "(no matches)";
        } else {
            output = String.join("\n", rendered);
        }

        if (timedOut) {
            if (output.equals("(no matches)")) {
                output = "[grep timed out after " + timeoutSeconds + " seconds]";
            } else {
                output = output + "\n\n[grep timed out after " + timeoutSeconds + " seconds]";
            }
        }

        return new ToolResult(output, timedOut);
    }

    private static String timeoutMarker(int timeoutSeconds) {
        return "__OPENHARNESS_GREP_TIMEOUT__:" + timeoutSeconds;
    }

    // ---- Pure Java fallback ----

    /**
     * Pure Java fallback for directory search using regex and file tree walk.
     */
    private ToolResult fallbackGrepTree(Path root, String regexPattern, String fileGlob,
                                         boolean caseSensitive, int limit) {
        // Collect files matching the file glob
        List<Path> files = new ArrayList<>();
        PathMatcher globMatcher = fileGlob != null && !fileGlob.isEmpty()
                && !"**/*".equals(fileGlob)
                ? java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + fileGlob)
                : null;

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> globMatcher == null || globMatcher.matches(root.relativize(p)))
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            return ToolResult.error("grep failed: " + e.getMessage());
        }

        return fallbackGrepFiles(files, regexPattern, caseSensitive, limit, root);
    }

    /**
     * Pure Java fallback for a list of files using java.util.regex.
     * Matches Python's {@code _python_grep_files}.
     */
    private ToolResult fallbackGrepFiles(List<Path> paths, String regexPattern,
                                          boolean caseSensitive, int limit, Path displayBase) {
        int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
        java.util.regex.Pattern compiled;
        try {
            compiled = java.util.regex.Pattern.compile(regexPattern, flags);
        } catch (PatternSyntaxException e) {
            return ToolResult.success("(invalid regex pattern '" + regexPattern + "': " + e.getMessage() + ")");
        }

        List<String> collected = new ArrayList<>();

        for (Path path : paths) {
            if (collected.size() >= limit) {
                break;
            }
            if (!Files.isRegularFile(path)) {
                continue;
            }

            byte[] raw;
            try {
                raw = Files.readAllBytes(path);
            } catch (IOException e) {
                continue;
            }

            // Binary file detection (Python checks for null bytes)
            boolean isBinary = false;
            for (byte b : raw) {
                if (b == 0) {
                    isBinary = true;
                    break;
                }
            }
            if (isBinary) {
                continue;
            }

            String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            for (int lineNo = 1; lineNo <= lines.length; lineNo++) {
                if (compiled.matcher(lines[lineNo - 1]).find()) {
                    collected.add(formatPath(path, displayBase) + ":" + lineNo + ":" + lines[lineNo - 1]);
                    if (collected.size() >= limit) {
                        break;
                    }
                }
            }
        }

        if (collected.isEmpty()) {
            return ToolResult.success("(no matches)");
        }
        return ToolResult.success(String.join("\n", collected));
    }

    // ---- Path helpers (matching Python utilities) ----

    /**
     * Resolve a possibly-relative path against the base directory.
     * Matches Python's {@code _resolve_path}.
     */
    private static Path resolvePath(Path base, String candidate) {
        Path path = Path.of(candidate != null ? candidate : ".");
        if (!path.isAbsolute()) {
            path = base.resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    /**
     * Determine the display base for path formatting.
     * If the path is within cwd, use cwd; otherwise use the path's parent.
     * Matches Python's {@code _display_base}.
     */
    private static Path displayBase(Path path, Path cwd) {
        try {
            path.toRealPath(); // trigger resolution
            // If path starts with cwd, it's relative-able
            if (path.startsWith(cwd)) {
                return cwd;
            }
        } catch (IOException e) {
            // Fall through
        }
        return path.getParent() != null ? path.getParent() : path;
    }

    /**
     * Format a path relative to the display base.
     * Matches Python's {@code _format_path}.
     */
    private static String formatPath(Path path, Path displayBase) {
        try {
            return displayBase.relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    // ---- Utility ----

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

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    public record Input(
            String pattern,
            String root,
            String file_glob,
            Boolean caseSensitive,  // wrapper to detect null → default true (matches Python)
            int limit,
            int timeout) {

        public Input {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("pattern is required");
            }
            if (file_glob == null) {
                file_glob = "**/*";
            }
            if (caseSensitive == null) {
                caseSensitive = true;  // Python default: case sensitive
            }
            if (limit <= 0) {
                limit = DEFAULT_LIMIT;
            } else if (limit > 2000) {
                limit = 2000;
            }
            if (timeout <= 0) {
                timeout = 20;
            } else if (timeout > 120) {
                timeout = 120;
            }
        }
    }
}
