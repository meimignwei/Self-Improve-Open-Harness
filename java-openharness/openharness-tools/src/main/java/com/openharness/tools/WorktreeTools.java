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
            super("enter_worktree", "Create and enter a git worktree for a branch.", Input.class);
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            Path cwd = context.cwd();
            String branch = arguments.branch();
            Path worktreePath = arguments.path() != null
                    ? cwd.resolve(arguments.path()).normalize()
                    : cwd.resolve(branch.replaceAll("[^a-zA-Z0-9_-]", "-")).normalize();

            StringBuilder cmd = new StringBuilder("git worktree add ");
            if (arguments.createBranch()) {
                cmd.append("-b ");
            }
            cmd.append("\"").append(worktreePath).append("\" \"").append(branch).append("\"");

            return runGit(cmd.toString(), cwd, 30);
        }

        public record Input(String branch, String path, boolean createBranch) {
            public Input {
                if (branch == null || branch.isBlank()) {
                    throw new IllegalArgumentException("branch is required");
                }
            }
        }
    }

    public static class ExitWorktreeTool extends BaseTool<ExitWorktreeTool.Input> {
        public ExitWorktreeTool() {
            super("exit_worktree", "Remove a git worktree.", Input.class);
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            Path cwd = context.cwd();
            Path worktreePath = arguments.path() != null
                    ? cwd.resolve(arguments.path()).normalize()
                    : cwd;

            if (!arguments.force()) {
                String status = runGit("git status --porcelain", cwd, 10).content();
                if (!status.isBlank() && !status.startsWith("Error")) {
                    return ToolResult.error("Worktree has uncommitted changes. Use force=true to remove anyway.");
                }
            }

            return runGit("git worktree remove \"" + worktreePath + "\"", cwd, 30);
        }

        public record Input(String path, boolean force) {}
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
                return ToolResult.error("Exit code " + exitCode + "\n" + stdout);
            }
            return ToolResult.success(stdout.isEmpty() ? "Done." : stdout);
        } catch (Exception e) {
            return ToolResult.error("Git command failed: " + e.getMessage());
        }
    }
}
