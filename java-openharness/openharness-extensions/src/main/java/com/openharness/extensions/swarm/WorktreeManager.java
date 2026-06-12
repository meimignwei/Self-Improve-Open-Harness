package com.openharness.extensions.swarm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Git worktree isolation for agent execution.
 * Java equivalent of Python swarm/worktree.py.
 */
public class WorktreeManager {

    public Path create(Path repo, String branch) throws IOException {
        Path wt = Files.createTempDirectory("worktree_");
        String wtStr = wt.toAbsolutePath().toString();

        ProcessBuilder pb = new ProcessBuilder(
                "git", "worktree", "add", "--detach", wtStr, branch);
        pb.directory(repo.toFile());

        try {
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new IOException("git worktree add failed with exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git worktree add interrupted", e);
        }

        return wt;
    }

    public Path create(Path repo) throws IOException {
        String branch;
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                    .directory(repo.toFile()).start();
            branch = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
        } catch (Exception e) {
            branch = "HEAD";
        }
        return create(repo, branch);
    }

    public void remove(Path worktree) throws IOException {
        try {
            Process p = new ProcessBuilder("git", "worktree", "remove",
                    worktree.toAbsolutePath().toString()).start();
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Lists all worktrees for a repo, returning paths and branches.
     */
    public String list(Path repo) throws IOException {
        Process p = new ProcessBuilder("git", "worktree", "list")
                .directory(repo.toFile()).start();
        try {
            return new String(p.getInputStream().readAllBytes());
        } catch (IOException e) {
            return "";
        }
    }

    public void prune(Path repo) throws IOException {
        try {
            new ProcessBuilder("git", "worktree", "prune")
                    .directory(repo.toFile()).start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
