package com.openharness.extensions.services;

import com.openharness.config.Paths;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Locking and session scanning for auto-dream.
 * Java equivalent of Python services/autodream/lock.py.
 */
public final class AutoDreamLock {

    private static final String LOCK_FILE = ".consolidate-lock";
    private static final long HOLDER_STALE_SECONDS = 60 * 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AutoDreamLock() {}

    // ── Path resolution ──

    private static Path lockPath(Path cwd, Path memoryDir) {
        return memoryDir != null
                ? memoryDir.resolve(LOCK_FILE)
                : Paths.projectMemoryDir(cwd).resolve(LOCK_FILE);
    }

    // ── Python: read_last_consolidated_at ──

    /**
     * Return lock mtime as the last successful consolidation timestamp (epoch seconds).
     */
    public static double readLastConsolidatedAt(Path cwd, Path memoryDir) {
        try {
            return Files.getLastModifiedTime(lockPath(cwd, memoryDir)).toInstant().getEpochSecond();
        } catch (IOException e) {
            return 0.0;
        }
    }

    // ── Python: try_acquire_consolidation_lock ──

    /**
     * Acquire the consolidation lock and return prior mtime (epoch seconds), or null if held.
     */
    public static Double tryAcquireConsolidationLock(Path cwd, Path memoryDir) {
        Path path = lockPath(cwd, memoryDir);
        double priorMtime = 0.0;
        Integer holder = null;
        try {
            priorMtime = Files.getLastModifiedTime(path).toInstant().getEpochSecond();
            holder = holderPid(path);
        } catch (IOException e) {
            // File doesn't exist or can't be read
        }

        if (priorMtime > 0 && Instant.now().getEpochSecond() - priorMtime < HOLDER_STALE_SECONDS) {
            if (holder != null && isProcessRunning(holder)) {
                return null;
            }
        }

        try {
            Files.createDirectories(path.getParent());
            atomicWriteText(path, String.valueOf(ProcessHandle.current().pid()));
            Integer written = holderPid(path);
            if (written == null || written != ProcessHandle.current().pid()) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return priorMtime;
    }

    // ── Python: rollback_consolidation_lock ──

    /**
     * Restore lock mtime to its pre-acquire value after failed/killed dream.
     */
    public static void rollbackConsolidationLock(Path cwd, double priorMtime, Path memoryDir) {
        Path path = lockPath(cwd, memoryDir);
        try {
            if (priorMtime <= 0) {
                Files.deleteIfExists(path);
                return;
            }
            atomicWriteText(path, "");
            Files.setLastModifiedTime(path,
                    java.nio.file.attribute.FileTime.from(Instant.ofEpochSecond((long) priorMtime)));
        } catch (IOException e) {
            // Best effort
        }
    }

    // ── Python: record_consolidation ──

    /**
     * Stamp a manual consolidation time.
     */
    public static void recordConsolidation(Path cwd, Path memoryDir) {
        Path path = lockPath(cwd, memoryDir);
        try {
            Files.createDirectories(path.getParent());
            atomicWriteText(path, String.valueOf(ProcessHandle.current().pid()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to record consolidation", e);
        }
    }

    // ── Python: list_sessions_touched_since ──

    /**
     * Return saved session IDs whose snapshot files were touched after sinceTs (epoch seconds).
     */
    @SuppressWarnings("unchecked")
    public static List<String> listSessionsTouchedSince(
            Path cwd, double sinceTs, String currentSessionId, Path sessionDir) {
        Path resolvedSessionDir = sessionDir != null
                ? sessionDir.toAbsolutePath().normalize()
                : Paths.projectSessionDir(cwd);
        List<String> sessionIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (!Files.exists(resolvedSessionDir)) return sessionIds;

        try (Stream<Path> files = Files.list(resolvedSessionDir)) {
            List<Path> sorted = files
                    .filter(f -> f.getFileName().toString().startsWith("session-")
                            && f.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> {
                        try {
                            return Long.compare(
                                    Files.getLastModifiedTime(b).toMillis(),
                                    Files.getLastModifiedTime(a).toMillis());
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .toList();
            for (Path path : sorted) {
                try {
                    double mtime = Files.getLastModifiedTime(path).toInstant().getEpochSecond();
                    if (mtime <= sinceTs) continue;
                } catch (IOException e) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                String sessionId = fileName.replaceAll("^session-", "").replaceAll("\\.json$", "");
                try {
                    java.util.Map<String, Object> payload = MAPPER.readValue(
                            Files.readString(path, StandardCharsets.UTF_8), java.util.Map.class);
                    Object rawId = payload.get("session_id");
                    if (rawId instanceof String s && !s.strip().isEmpty()) {
                        sessionId = s.strip();
                    }
                } catch (IOException e) {
                    // Use filename-derived ID
                }
                if (currentSessionId != null && sessionId.equals(currentSessionId)) continue;
                if (seen.contains(sessionId)) continue;
                seen.add(sessionId);
                sessionIds.add(sessionId);
            }
        } catch (IOException e) {
            // Return what we have
        }
        return sessionIds;
    }

    // ── Private helpers ──

    private static void atomicWriteText(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tempPath, content, StandardCharsets.UTF_8);
        Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Integer holderPid(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8).strip();
            int pid = Integer.parseInt(raw);
            return pid > 0 ? pid : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private static boolean isProcessRunning(int pid) {
        if (pid == (int) ProcessHandle.current().pid()) return true;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
