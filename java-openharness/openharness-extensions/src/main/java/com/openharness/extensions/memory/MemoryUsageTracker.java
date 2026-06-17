package com.openharness.extensions.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * File-persisted usage tracking for recalled memory entries.
 * Java equivalent of Python memory/usage.py.
 *
 * usage_index.json format:
 * {"version": 1, "memories": {"id": {"use_count": N, "last_used_at": "...", "path": "..."}}}
 */
public class MemoryUsageTracker {

    private static final String USAGE_INDEX_NAME = "usage_index.json";
    private static final int STALE_UNUSED_DAYS = 60;
    private static final int STALE_MAX_IMPORTANCE = 1;

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** In-memory cache for fast lookups. */
    private final Map<String, AtomicInteger> usageCounts = new ConcurrentHashMap<>();
    /** Path to the memory directory. */
    private Path memoryDir;

    public MemoryUsageTracker() {
        this.memoryDir = null;
    }

    public MemoryUsageTracker(Path memoryDir) {
        this.memoryDir = memoryDir;
    }

    public void setMemoryDir(Path memoryDir) {
        this.memoryDir = memoryDir;
    }

    // ------------------------------------------------------------------
    // In-memory access (fast path)
    // ------------------------------------------------------------------

    public void recordUsage(String memoryId) {
        usageCounts.computeIfAbsent(memoryId, k -> new AtomicInteger()).incrementAndGet();
    }

    public int getUsageCount(String memoryId) {
        AtomicInteger count = usageCounts.get(memoryId);
        return count != null ? count.get() : 0;
    }

    public double computeUsageBoost(String memoryId) {
        return Math.min(getUsageCount(memoryId), 5) * 0.1;
    }

    public Map<String, Integer> snapshot() {
        Map<String, Integer> result = new ConcurrentHashMap<>();
        usageCounts.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public void reset(String memoryId) {
        usageCounts.remove(memoryId);
    }

    // ------------------------------------------------------------------
    // File persistence — matching Python load_usage_index / save_usage_index
    // ------------------------------------------------------------------

    /**
     * Python: get_usage_index_path(cwd, memory_dir=memory_dir)
     */
    public Path getUsageIndexPath() {
        if (memoryDir == null) {
            throw new IllegalStateException("memoryDir not set on MemoryUsageTracker");
        }
        Path root = memoryDir;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory dir", e);
        }
        return root.resolve(USAGE_INDEX_NAME);
    }

    /**
     * Python load_usage_index: read usage_index.json, normalize records.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadUsageIndex() {
        Path path = getUsageIndexPath();
        if (!Files.exists(path)) {
            return emptyIndex();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, Object> data = MAPPER.readValue(content, Map.class);
            if (data == null) return emptyIndex();

            Map<String, Object> normalized = emptyIndex();
            Object memoriesObj = data.get("memories");
            Map<String, Object> sourceMemories;
            if (memoriesObj instanceof Map) {
                sourceMemories = (Map<String, Object>) memoriesObj;
            } else {
                sourceMemories = Map.of();
            }
            Map<String, Object> targetMemories = (Map<String, Object>) normalized.get("memories");
            for (var entry : sourceMemories.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> record) {
                    targetMemories.put(entry.getKey(), normalizeUsageRecord((Map<String, Object>) record));
                }
            }
            // Sync in-memory cache
            for (var entry : targetMemories.entrySet()) {
                Object record = entry.getValue();
                if (record instanceof Map<?, ?> m) {
                    Object count = m.get("use_count");
                    int c = count instanceof Number n ? n.intValue() : 0;
                    usageCounts.put(entry.getKey(), new AtomicInteger(c));
                }
            }
            return normalized;
        } catch (IOException e) {
            return emptyIndex();
        }
    }

    /**
     * Python save_usage_index: atomic write usage_index.json.
     */
    public void saveUsageIndex(Map<String, Object> index) {
        Path path = getUsageIndexPath();
        try {
            String payload = MAPPER.writeValueAsString(index);
            if (!payload.endsWith("\n")) payload += "\n";
            Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tempPath, payload, StandardCharsets.UTF_8);
            Files.move(tempPath, path,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save usage index: " + path, e);
        }
    }

    /**
     * Python mark_memory_used: lock → load → increment → save → unlock.
     */
    public void markMemoryUsed(List<MemoryScanHeader> memories) {
        List<MemoryScanHeader> usable = memories.stream()
                .filter(h -> h.id() != null && !h.id().isBlank())
                .toList();
        if (usable.isEmpty()) return;

        Path resolvedMemoryDir = memoryDir != null ? memoryDir : usable.get(0).path().getParent();
        Path lockPath = resolvedMemoryDir.resolve(".usage_index.lock");

        try (MemoryLock ignored = MemoryLock.acquire(lockPath)) {
            // Temporarily set memoryDir so loadUsageIndex reads from correct location
            Path previousDir = this.memoryDir;
            this.memoryDir = resolvedMemoryDir;
            try {
                Map<String, Object> index = loadUsageIndex();
                String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
                @SuppressWarnings("unchecked")
                Map<String, Object> memoriesMap = (Map<String, Object>) index.get("memories");
                for (MemoryScanHeader header : usable) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> record = (Map<String, Object>) memoriesMap.getOrDefault(
                            header.id(), normalizeUsageRecord(Map.of()));
                    record = normalizeUsageRecord(record);
                    record.put("use_count", ((Number) record.get("use_count")).intValue() + 1);
                    record.put("last_used_at", now);
                    record.put("path", header.path().getFileName().toString());
                    memoriesMap.put(header.id(), record);

                    // Update in-memory too
                    usageCounts.computeIfAbsent(header.id(), k -> new AtomicInteger()).incrementAndGet();
                }
                saveUsageIndex(index);
            } finally {
                this.memoryDir = previousDir;
            }
        }
    }

    /**
     * Python find_stale_memory_candidates: unused, low-importance, >60 days old.
     */
    public List<MemoryScanHeader> findStaleMemoryCandidates(Path memoryDir) {
        List<MemoryScanHeader> headers = MemoryScanner.scanMemoryFiles(
                memoryDir, null, false, false);
        Instant now = Instant.now();
        List<MemoryScanHeader> candidates = new ArrayList<>();

        for (MemoryScanHeader header : headers) {
            if (header.importance() > STALE_MAX_IMPORTANCE) continue;

            int useCount = getUsageCount(header.id());

            // Also check file-based index if in-memory is 0
            if (useCount == 0 && this.memoryDir != null) {
                Map<String, Object> index = loadUsageIndex();
                @SuppressWarnings("unchecked")
                Map<String, Object> memoriesMap = (Map<String, Object>) index.get("memories");
                Object record = memoriesMap.get(header.id());
                if (record instanceof Map<?, ?> m) {
                    Object count = m.get("use_count");
                    useCount = count instanceof Number n ? n.intValue() : 0;
                }
            }

            if (useCount > 0) continue;

            Instant updatedAt = header.header().updatedAt() != null
                    ? header.header().updatedAt() : header.header().createdAt();
            if (updatedAt == null) continue;

            if (java.time.Duration.between(updatedAt, now).toDays() >= STALE_UNUSED_DAYS) {
                candidates.add(header);
            }
        }

        candidates.sort(java.util.Comparator.comparing(MemoryScanHeader::importance)
                .thenComparing(h -> h.header().updatedAt() != null
                        ? h.header().updatedAt().toString() : "")
                .thenComparing(h -> h.path().getFileName().toString()));
        return candidates;
    }

    public void loadFrom(Map<String, Integer> data) {
        usageCounts.clear();
        data.forEach((k, v) -> usageCounts.put(k, new AtomicInteger(v)));
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> emptyIndex() {
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("version", 1);
        index.put("memories", new LinkedHashMap<>());
        return index;
    }

    private static Map<String, Object> normalizeUsageRecord(Map<String, Object> record) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        int useCount = 0;
        Object countObj = record.get("use_count");
        if (countObj instanceof Number n) {
            useCount = Math.max(0, n.intValue());
        } else if (countObj instanceof String s) {
            try { useCount = Math.max(0, Integer.parseInt(s)); } catch (NumberFormatException ignored) {}
        }
        normalized.put("last_used_at", record.getOrDefault("last_used_at", "").toString());
        normalized.put("use_count", useCount);
        normalized.put("path", record.getOrDefault("path", "").toString());
        return normalized;
    }
}
