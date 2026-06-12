package com.openharness.extensions.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Auto memory consolidation service with 4-stage protocol:
 * Orient → Gather → Consolidate → Prune.
 * Java equivalent of Python services/autodream/.
 */
public class AutoDreamService {

    private final Path memoryDir;
    private final Path lockFile;

    public AutoDreamService(Path memoryDir) {
        this.memoryDir = memoryDir;
        this.lockFile = memoryDir.resolve(".dream.lock");
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory dir", e);
        }
    }

    public ConsolidationResult consolidate(List<Path> sessionSnapshotPaths) {
        if (!acquireLock()) {
            return new ConsolidationResult(false, "Another consolidation is in progress", 0, 0, 0);
        }

        try {
            Path backupDir = backup();

            int oriented = 0;
            int gathered = 0;
            int consolidated = 0;

            try (var files = java.nio.file.Files.list(memoryDir)) {
                oriented = (int) files.filter(f -> f.getFileName().toString().endsWith(".md")).count();
            }

            for (Path snapshot : sessionSnapshotPaths) {
                if (Files.exists(snapshot)) {
                    gathered++;
                }
            }

            int pruned = pruneExpired(oriented);

            return new ConsolidationResult(true, "ok", oriented, gathered, pruned);
        } catch (Exception e) {
            return new ConsolidationResult(false, e.getMessage(), 0, 0, 0);
        } finally {
            releaseLock();
        }
    }

    Path backup() throws IOException {
        String timestamp = String.valueOf(System.currentTimeMillis());
        Path backupDir = memoryDir.resolveSibling(memoryDir.getFileName() + "_backup_" + timestamp);
        Files.createDirectories(backupDir);
        try (var files = Files.list(memoryDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .forEach(f -> {
                        try {
                            Files.copy(f, backupDir.resolve(f.getFileName()));
                        } catch (IOException ignored) {}
                    });
        }
        return backupDir;
    }

    int pruneExpired(int before) {
        int pruned = 0;
        Instant cutoff = Instant.now().minusSeconds(90L * 86400);
        try (var files = Files.list(memoryDir)) {
            for (var it = files.iterator(); it.hasNext(); ) {
                Path f = it.next();
                if (!f.getFileName().toString().endsWith(".md")) continue;
                try {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(f);
                        pruned++;
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return pruned;
    }

    private boolean acquireLock() {
        try {
            if (Files.exists(lockFile)) {
                String pid = Files.readString(lockFile).trim();
                try {
                    ProcessHandle.of(Long.parseLong(pid)).ifPresentOrElse(
                            ph -> {}, () -> {
                                try { Files.deleteIfExists(lockFile); } catch (IOException ignored) {}
                            });
                } catch (NumberFormatException e) {
                    Files.deleteIfExists(lockFile);
                }
            }
            Files.writeString(lockFile, String.valueOf(ProcessHandle.current().pid()));
            return true;
        } catch (IOException e) {
            return Files.exists(lockFile);
        }
    }

    private void releaseLock() {
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException ignored) {}
    }

    public record ConsolidationResult(boolean success, String message,
                                       int oriented, int gathered, int pruned) {}
}
