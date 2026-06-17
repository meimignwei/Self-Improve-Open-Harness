package com.openharness.extensions.services;

import com.openharness.config.Paths;
import com.openharness.extensions.memory.MemoryUsageTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * Auto memory consolidation service with 4-stage protocol:
 * Orient → Gather → Consolidate → Prune.
 * Java equivalent of Python services/autodream/service.py.
 */
public class AutoDreamService {

    private static final String CHILD_ENV = "OPENHARNESS_AUTODREAM_CHILD";
    private static final int SESSION_SCAN_INTERVAL_SECONDS = 10 * 60;

    private long lastSessionScanAt;

    private final Path cwd;
    private final Path memoryDir;
    private final Path sessionDir;
    private final double minHours;
    private final int minSessions;
    private final String appLabel;
    private final BiFunction<String, String, String> llmCall;

    public AutoDreamService(Path cwd, Path memoryDir, Path sessionDir,
                            double minHours, int minSessions, String appLabel,
                            BiFunction<String, String, String> llmCall) {
        this.cwd = cwd.toAbsolutePath().normalize();
        this.memoryDir = memoryDir.toAbsolutePath().normalize();
        this.sessionDir = sessionDir != null
                ? sessionDir.toAbsolutePath().normalize()
                : Paths.projectSessionDir(cwd);
        this.minHours = minHours > 0 ? minHours : 24;
        this.minSessions = minSessions > 0 ? minSessions : 5;
        this.appLabel = appLabel != null ? appLabel : "openharness";
        this.llmCall = llmCall;
        this.lastSessionScanAt = 0;
        try {
            Files.createDirectories(memoryDir);
            Files.createDirectories(this.sessionDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory/session dirs", e);
        }
    }

    /**
     * Python start_dream_now: full consolidation flow.
     *
     * @param force             bypass time/session gates
     * @param currentSessionId  current session to exclude from scan
     * @param preview           preview mode (no actual writes)
     */
    public ConsolidationResult startDreamNow(boolean force, String currentSessionId, boolean preview) {
        if (System.getenv(CHILD_ENV) != null) {
            return new ConsolidationResult(false, "already in child process", null, 0, 0, 0);
        }

        double lastAt = AutoDreamLock.readLastConsolidatedAt(cwd, memoryDir);
        List<String> sessionIds = AutoDreamLock.listSessionsTouchedSince(
                cwd, lastAt, currentSessionId, sessionDir);

        if (!force) {
            double hoursSince = (Instant.now().getEpochSecond() - lastAt) / 3600.0;
            if (hoursSince < minHours) {
                return new ConsolidationResult(false,
                        "too soon since last consolidation (" + String.format("%.1f", hoursSince) + "h < " + minHours + "h)",
                        null, 0, 0, 0);
            }
            if (sessionIds.size() < minSessions) {
                return new ConsolidationResult(false,
                        "not enough sessions (" + sessionIds.size() + " < " + minSessions + ")",
                        null, 0, 0, 0);
            }
        }

        if (!hasDreamSignal(sessionIds, force)) {
            return new ConsolidationResult(false, "no dream signal", null, 0, 0, 0);
        }

        Double priorMtime = AutoDreamLock.tryAcquireConsolidationLock(cwd, memoryDir);
        if (priorMtime == null) {
            return new ConsolidationResult(false, "consolidation lock held by another process", null, 0, 0, 0);
        }

        // Snapshot before-file mtimes for change detection
        Map<String, Long> before = memoryFilesMtimeSnapshot(memoryDir);
        Path backupDir = null;
        if (!preview) {
            backupDir = AutoDreamBackup.createMemoryBackup(memoryDir, appLabel);
        }

        // Build stale candidates section
        MemoryUsageTracker tracker = new MemoryUsageTracker(memoryDir);
        var staleCandidates = tracker.findStaleMemoryCandidates(memoryDir);
        StringBuilder staleSection = new StringBuilder();
        int staleLimit = Math.min(staleCandidates.size(), 20);
        for (int i = 0; i < staleLimit; i++) {
            var header = staleCandidates.get(i);
            staleSection.append("- ").append(header.id() != null ? header.id() : header.path().getFileName())
                    .append(": ").append(header.path().getFileName())
                    .append(" (importance=").append(header.importance())
                    .append(", updated_at=").append(header.updatedAt() != null ? header.updatedAt() : "unknown")
                    .append(")\n");
        }
        if (staleSection.isEmpty()) staleSection.append("- (none)\n");

        String extra = "Application context: `" + appLabel + "`.\n"
                + "Tool constraints for this run: only modify files under the memory directory. "
                + "Use shell commands only for read-only inspection.\n\n"
                + "Sessions since last consolidation (" + sessionIds.size() + "):\n"
                + String.join("\n", sessionIds.stream().map(id -> "- " + id).toList())
                + "\n\nUsage-based stale candidates:\n"
                + staleSection;

        String prompt = AutoDreamPrompt.buildConsolidationPrompt(memoryDir, sessionDir, extra, preview);
        String systemPrompt = "You maintain OpenHarness durable memory. Follow the consolidation protocol.";

        String response;
        try {
            response = llmCall.apply(systemPrompt, prompt);
        } catch (Exception e) {
            if (backupDir != null) {
                try {
                    AutoDreamBackup.restoreMemoryBackup(backupDir, memoryDir);
                } catch (IOException ignored) {
                }
            }
            AutoDreamLock.rollbackConsolidationLock(cwd, priorMtime, memoryDir);
            return new ConsolidationResult(false, "LLM call failed: " + e.getMessage(), null, 0, 0, 0);
        }

        // Diff after consolidation
        Map<String, List<String>> diff = AutoDreamBackup.diffMemoryDirs(backupDir != null ? backupDir : memoryDir, memoryDir);
        List<String> changed = filesChangedSince(memoryDir, before);
        String diffSummary = AutoDreamBackup.formatMemoryDiff(diff);

        int oriented = countMdFiles(memoryDir);
        int pruned = pruneExpired(memoryDir);

        return new ConsolidationResult(true, diffSummary, response,
                oriented, sessionIds.size(), pruned);
    }

    /**
     * Python execute_auto_dream: cheap gates then start dream when eligible.
     */
    public ConsolidationResult executeAutoDream(String currentSessionId) {
        if (System.getenv(CHILD_ENV) != null) return null;

        double lastAt = AutoDreamLock.readLastConsolidatedAt(cwd, memoryDir);
        double hoursSince = (Instant.now().getEpochSecond() - lastAt) / 3600.0;
        if (hoursSince < minHours) return null;

        long now = System.currentTimeMillis();
        if (now - lastSessionScanAt < SESSION_SCAN_INTERVAL_SECONDS * 1000L) return null;
        lastSessionScanAt = now;

        List<String> sessionIds = AutoDreamLock.listSessionsTouchedSince(
                cwd, lastAt, currentSessionId, sessionDir);
        if (sessionIds.size() < minSessions) return null;
        if (!hasDreamSignal(sessionIds, false)) return null;

        return startDreamNow(false, currentSessionId, false);
    }

    /**
     * Legacy consolidate method for backward compatibility with GatewayEngineFactory.
     */
    public ConsolidationResult consolidate(List<Path> sessionSnapshotPaths) {
        return startDreamNow(false, null, false);
    }

    /**
     * Python schedule_auto_dream: fire-and-forget scheduling.
     */
    public static void scheduleAutoDream(AutoDreamService service) {
        Thread.ofVirtual()
                .name("autodream-scheduler")
                .start(() -> {
                    try {
                        service.executeAutoDream(null);
                    } catch (Exception ignored) {
                    }
                });
    }

    // ── Python: _has_dream_signal ──

    private static boolean hasDreamSignal(List<String> sessionIds, boolean force) {
        if (force) return true;
        return !sessionIds.isEmpty();
    }

    // ── Python: _memory_files_mtime_snapshot ──

    static Map<String, Long> memoryFilesMtimeSnapshot(Path memoryDir) {
        java.util.Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .forEach(f -> {
                        try {
                            snapshot.put(f.getFileName().toString(),
                                    Files.getLastModifiedTime(f).toMillis());
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        return snapshot;
    }

    // ── Python: _files_changed_since ──

    static List<String> filesChangedSince(Path memoryDir, Map<String, Long> before) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .forEach(f -> {
                        try {
                            long mtime = Files.getLastModifiedTime(f).toMillis();
                            Long beforeMtime = before.get(f.getFileName().toString());
                            if (beforeMtime == null || beforeMtime != mtime) {
                                changed.add(f.getFileName().toString());
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        return changed;
    }

    // ── Pruning ──

    static int pruneExpired(Path memoryDir) {
        int pruned = 0;
        Instant cutoff = Instant.now().minusSeconds(90L * 86400);
        try (Stream<Path> files = Files.list(memoryDir)) {
            for (Path f : files.toList()) {
                if (!f.getFileName().toString().endsWith(".md")) continue;
                if ("MEMORY.md".equals(f.getFileName().toString())) continue;
                try {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(f);
                        pruned++;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return pruned;
    }

    private static int countMdFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(f -> f.getFileName().toString().endsWith(".md")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ── Result type ──

    public record ConsolidationResult(
            boolean success,
            String message,
            String llmResponse,
            int oriented,
            int gathered,
            int pruned
    ) {
        public ConsolidationResult {
            if (message == null) message = "";
        }
    }
}
