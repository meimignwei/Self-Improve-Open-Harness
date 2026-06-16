package com.openharness.extensions.swarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Git worktree isolation for agent execution.
 * Java equivalent of Python swarm/worktree.py.
 */
public final class WorktreeManager {

    private static final Logger logger = LoggerFactory.getLogger(WorktreeManager.class);

    private WorktreeManager() {}

    // ------------------------------------------------------------------
    // Worktree CRUD
    // ------------------------------------------------------------------

    public static Path createWorktree(Path repo, String branch) throws IOException {
        Path wt = getWorktreesBaseDir().resolve("wt_" + UUID.randomUUID().toString().substring(0, 8));
        return createWorktreeAt(repo, branch, wt);
    }

    public static Path createWorktree(Path repo) throws IOException {
        String branch = getCurrentBranch(repo);
        return createWorktree(repo, branch != null ? branch : "HEAD");
    }

    public static Path createWorktreeForAgent(Path repo, String agentId) throws IOException {
        Path wt = getAgentWorktreeDir(agentId);
        String branch = getCurrentBranch(repo);
        return createWorktreeAt(repo, branch != null ? branch : "HEAD", wt);
    }

    private static Path createWorktreeAt(Path repo, String branch, Path wt) throws IOException {
        Files.createDirectories(wt.getParent());

        ProcessBuilder pb = new ProcessBuilder(
                "git", "worktree", "add", "--detach",
                wt.toAbsolutePath().toString(), branch);
        pb.directory(repo.toAbsolutePath().toFile());

        try {
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                String stderr = new String(p.getErrorStream().readAllBytes());
                throw new IOException("git worktree add failed (exit=" + exitCode + "): " + stderr);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git worktree add interrupted", e);
        }

        logger.debug("Created worktree at {} for branch {}", wt, branch);
        return wt;
    }

    public static void removeWorktree(Path worktree) throws IOException {
        // Find the main repo by reading the .git file in the worktree
        Path repo = findMainRepo(worktree);
        Path baseRepo = repo != null ? repo : worktree;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "worktree", "remove", "--force",
                    worktree.toAbsolutePath().toString());
            pb.directory(baseRepo.toFile());
            Process p = pb.start();
            p.waitFor(30, TimeUnit.SECONDS);
            logger.debug("Removed worktree: {}", worktree);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("git worktree remove failed for {}, falling back to rm", worktree, e);
            destroyWorktree(worktree);
        }
    }

    public static List<String> listWorktrees(Path repo) throws IOException {
        List<String> result = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "list", "--porcelain");
            pb.directory(repo.toFile());
            Process p = pb.start();
            p.waitFor();
            String output = new String(p.getInputStream().readAllBytes());
            for (String line : output.split("\n")) {
                if (line.startsWith("worktree ")) {
                    result.add(line.substring("worktree ".length()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    public static void pruneWorktrees(Path repo) {
        try {
            new ProcessBuilder("git", "worktree", "prune")
                    .directory(repo.toFile()).start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("git worktree prune failed (non-critical): {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Worktree paths
    // ------------------------------------------------------------------

    public static Path getWorktreesBaseDir() {
        return Path.of(System.getProperty("user.home"), ".openharness", "worktrees");
    }

    public static Path getAgentWorktreeDir(String agentId) {
        return getWorktreesBaseDir().resolve(sanitizeForPath(agentId));
    }

    // ------------------------------------------------------------------
    // Symlink common directories (node_modules, .venv, etc.)
    // ------------------------------------------------------------------

    private static final List<String> COMMON_DIRS = List.of(
            "node_modules", ".venv", ".tox", "__pycache__", "venv", ".env");

    /**
     * Create symlinks from the original repo's common directories to the worktree.
     * This avoids re-downloading or re-creating large directories per worktree.
     */
    public static void symlinkCommonDirs(Path worktree, Path originalRepo) {
        if (!Files.exists(originalRepo)) return;

        for (String dirName : COMMON_DIRS) {
            Path source = originalRepo.resolve(dirName);
            Path target = worktree.resolve(dirName);

            if (Files.exists(source) && !Files.exists(target)) {
                try {
                    Files.createSymbolicLink(target, source.toAbsolutePath());
                    logger.debug("Symlinked {} -> {}", target, source);
                } catch (IOException e) {
                    logger.debug("Failed to symlink {}: {}", dirName, e.getMessage());
                }
            }
        }
    }

    /**
     * Remove symlinks created by {@link #symlinkCommonDirs}.
     */
    public static void removeSymlinks(Path worktree) {
        if (!Files.exists(worktree)) return;

        for (String dirName : COMMON_DIRS) {
            Path target = worktree.resolve(dirName);
            if (Files.isSymbolicLink(target)) {
                try {
                    Files.deleteIfExists(target);
                    logger.debug("Removed symlink: {}", target);
                } catch (IOException e) {
                    logger.debug("Failed to remove symlink {}: {}", dirName, e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    public static void destroyWorktree(Path worktreePath) {
        if (!Files.exists(worktreePath)) return;

        // Try git worktree remove first
        Path repo = findMainRepo(worktreePath);
        if (repo != null) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "git", "worktree", "remove", "--force",
                        worktreePath.toAbsolutePath().toString());
                pb.directory(repo.toFile());
                Process p = pb.start();
                if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return;
                }
            } catch (Exception e) {
                // fall through to rm -rf
            }
        }

        // Fallback: delete the directory
        try {
            deleteRecursive(worktreePath);
        } catch (IOException e) {
            logger.warn("Failed to delete worktree directory: {}", worktreePath, e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Path findMainRepo(Path worktreePath) {
        Path gitFile = worktreePath.resolve(".git");
        if (!Files.exists(gitFile)) return null;

        try {
            String content = Files.readString(gitFile).trim();
            if (content.startsWith("gitdir:")) {
                String gitDir = content.substring("gitdir:".length()).trim();
                // Navigate up from .git/worktrees/<name>/gitdir to the main repo
                Path resolvedGitDir = worktreePath.resolve(gitDir).normalize();
                // The main repo is the parent of the .git directory
                Path mainGitDir = resolvedGitDir;
                while (mainGitDir != null && !mainGitDir.getFileName().toString().equals(".git")) {
                    mainGitDir = mainGitDir.getParent();
                }
                if (mainGitDir != null && mainGitDir.getParent() != null) {
                    return mainGitDir.getParent();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String getCurrentBranch(Path repo) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(repo.toFile());
            Process p = pb.start();
            p.waitFor();
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            return "HEAD";
        }
    }

    private static String sanitizeForPath(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-@]", "_");
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder())
                        .forEach(f -> {
                            try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                        });
            }
        }
    }
}