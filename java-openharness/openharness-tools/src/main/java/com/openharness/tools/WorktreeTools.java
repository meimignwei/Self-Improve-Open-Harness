package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Git worktree management tools.
 */
public final class WorktreeTools {

    private WorktreeTools() {}

    public static class EnterWorktreeTool extends BaseTool<EnterWorktreeTool.Input> {
        public EnterWorktreeTool() {
            super("enter_worktree", "Create a git worktree and return its path.", Input.class);
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            Path cwd = context.cwd();

            // Validate we're in a git repo
            String topLevel = gitOutput(cwd, "rev-parse", "--show-toplevel");
            if (topLevel == null) {
                return ToolResult.error("enter_worktree requires a git repository");
            }
            Path repoRoot = Path.of(topLevel);

            String branch = arguments.branch();
            boolean createBranch = arguments.createBranch() != null && arguments.createBranch();
            String baseRef = arguments.baseRef() != null ? arguments.baseRef() : "HEAD";

            // Resolve worktree path
            Path worktreePath;
            if (arguments.path() != null && !arguments.path().isBlank()) {
                Path given = Path.of(arguments.path());
                if (!given.isAbsolute()) {
                    worktreePath = repoRoot.resolve(given).normalize();
                } else {
                    worktreePath = given.normalize();
                }
            } else {
                String slug = branch.replaceAll("[^A-Za-z0-9._-]+", "-")
                        .replaceAll("^-+|-+$", "");
                if (slug.isEmpty()) slug = "worktree";
                worktreePath = repoRoot.resolve(".openharness").resolve("worktrees").resolve(slug).normalize();
            }

            // Create parent directories
            try {
                Files.createDirectories(worktreePath.getParent());
            } catch (Exception e) {
                return ToolResult.error("Failed to create worktree parent directory: " + e.getMessage());
            }

            // Build git command
            StringBuilder cmd = new StringBuilder("git worktree add ");
            if (createBranch) {
                cmd.append("-b ").append(quote(branch)).append(" ")
                   .append(quote(worktreePath.toString())).append(" ")
                   .append(quote(baseRef));
            } else {
                cmd.append(quote(worktreePath.toString())).append(" ")
                   .append(quote(branch));
            }

            ToolResult result = runGit(cmd.toString(), repoRoot, 30);
            if (result.isError()) {
                return result;
            }
            String output = result.content();
            if (output == null || output.isBlank()) {
                output = "Created worktree " + worktreePath;
            }
            return ToolResult.success(output + "\nPath: " + worktreePath);
        }

        public record Input(String branch, String path, Boolean createBranch, String baseRef) {
            public Input {
                if (branch == null || branch.isBlank()) {
                    throw new IllegalArgumentException("branch is required");
                }
                if (createBranch == null) createBranch = true;
                if (baseRef == null || baseRef.isBlank()) baseRef = "HEAD";
            }
        }
    }

    public static class ExitWorktreeTool extends BaseTool<ExitWorktreeTool.Input> {
        public ExitWorktreeTool() {
            super("exit_worktree", "Remove a git worktree by path.", Input.class);
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            Path cwd = context.cwd();
            Path worktreePath;
            if (arguments.path() != null && !arguments.path().isBlank()) {
                Path given = Path.of(arguments.path());
                if (!given.isAbsolute()) {
                    worktreePath = cwd.resolve(given).normalize();
                } else {
                    worktreePath = given.normalize();
                }
            } else {
                worktreePath = cwd;
            }

            String cmd = "git worktree remove --force " + quote(worktreePath.toString());
            ToolResult result = runGit(cmd, cwd, 30);
            if (result.isError()) {
                return result;
            }
            return ToolResult.success("Removed worktree " + worktreePath);
        }

        public record Input(String path) {
            public Input {
                // path is nullable - defaults to cwd in execute()
            }
        }
    }

    private static String gitOutput(Path cwd, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            return output.toString().stripTrailing();
        } catch (Exception e) {
            return null;
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    private static ToolResult runGit(String command, Path cwd, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Git command timed out after " + timeoutSeconds + " seconds");
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.exitValue();
            String stdout = output.toString().stripTrailing();
            if (exitCode != 0) {
                return ToolResult.error(stdout.isEmpty() ? "Exit code " + exitCode : stdout);
            }
            return ToolResult.success(stdout);
        } catch (Exception e) {
            return ToolResult.error("Git command failed: " + e.getMessage());
        }
    }
}
