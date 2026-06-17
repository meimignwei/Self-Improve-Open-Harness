package com.openharness.extensions.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Relevance-based memory selection and formatting.
 * Java equivalent of Python memory/relevance.py.
 */
public class MemoryRelevance {

    /**
     * Python MemorySelector: (query, headers) → selected relative paths.
     */
    @FunctionalInterface
    public interface MemorySelector extends BiFunction<String, List<MemoryScanHeader>, List<String>> {}

    /**
     * A memory selected for prompt injection.
     */
    public record RelevantMemory(MemoryScanHeader header, String freshness) {}

    // ------------------------------------------------------------------
    // Python: build_memory_manifest
    // ------------------------------------------------------------------

    /**
     * Render a compact manifest for selector prompts and diagnostics.
     * Format: [type] relative_path (age) - description
     */
    public static String buildMemoryManifest(List<MemoryScanHeader> headers) {
        StringBuilder sb = new StringBuilder();
        for (MemoryScanHeader header : headers) {
            String prefix = "[" + (header.memoryType() != null && !header.memoryType().isEmpty()
                    ? header.memoryType() : "memory") + "]";
            sb.append(prefix).append(" ")
                    .append(header.relativePath()).append(" ")
                    .append("(").append(memoryAgeLabel(header.modifiedAt())).append(")");
            if (header.description() != null && !header.description().isEmpty()) {
                sb.append(" - ").append(header.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Python: select_relevant_memories
    // ------------------------------------------------------------------

    /**
     * Return relevant memories with duplicate and freshness handling.
     */
    public static List<RelevantMemory> selectRelevantMemories(
            String query, Path memoryDir, int maxResults,
            Set<String> alreadySurfaced, MemorySelector selector) {
        Set<String> surfaced = alreadySurfaced != null ? alreadySurfaced : Set.of();

        MemorySearch search = new MemorySearch();
        List<MemoryScanHeader> heuristic = search.findRelevantMemories(
                query, memoryDir, Math.max(10, maxResults * 3));
        heuristic = heuristic.stream()
                .filter(h -> !surfaced.contains(h.relativePath()))
                .toList();

        List<MemoryScanHeader> selected = applySelector(query, heuristic, selector, maxResults);
        List<RelevantMemory> result = new ArrayList<>();
        for (MemoryScanHeader header : selected.subList(0, Math.min(maxResults, selected.size()))) {
            result.add(new RelevantMemory(header, memoryFreshnessText(header.modifiedAt())));
        }
        return result;
    }

    /**
     * Python select_manifest_memories: select from full manifest instead of heuristic matches only.
     */
    public static List<RelevantMemory> selectManifestMemories(
            String query, Path memoryDir, int maxResults, MemorySelector selector) {
        List<MemoryScanHeader> headers = MemoryScanner.scanMemoryFiles(memoryDir, 200);
        List<MemoryScanHeader> selected = applySelector(query, headers, selector, maxResults);
        List<RelevantMemory> result = new ArrayList<>();
        for (MemoryScanHeader header : selected.subList(0, Math.min(maxResults, selected.size()))) {
            result.add(new RelevantMemory(header, memoryFreshnessText(header.modifiedAt())));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Python: format_relevant_memories
    // ------------------------------------------------------------------

    /**
     * Render selected memories for prompt context.
     */
    public static String formatRelevantMemories(List<RelevantMemory> memories, int maxChars) {
        StringBuilder sb = new StringBuilder("# Relevant Memories");
        for (RelevantMemory item : memories) {
            MemoryScanHeader h = item.header();
            sb.append("\n\n## ").append(h.relativePath());
            if (!item.freshness().isEmpty()) {
                sb.append("\n> ").append(item.freshness());
            }
            try {
                String content = Files.readString(h.path(), StandardCharsets.UTF_8).strip();
                sb.append("\n```md\n").append(
                        content.length() > maxChars ? content.substring(0, maxChars) : content)
                        .append("\n```");
            } catch (IOException e) {
                sb.append("\n```md\n").append(h.bodyPreview()).append("\n```");
            }
        }
        return sb.append("\n").toString();
    }

    public static String formatRelevantMemories(List<RelevantMemory> memories) {
        return formatRelevantMemories(memories, 8000);
    }

    // ------------------------------------------------------------------
    // Python: json_selector_from_text
    // ------------------------------------------------------------------

    /**
     * Parse a selector response as either JSON list or newline paths.
     */
    @SuppressWarnings("unchecked")
    public static List<String> jsonSelectorFromText(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) return List.of();

        // Try JSON parse
        try {
            Object parsed = com.openharness.common.OpenHarnessObjectMapper.get()
                    .readValue(stripped, Object.class);
            if (parsed instanceof List<?> l) {
                return l.stream().map(Object::toString).map(String::strip)
                        .filter(s -> !s.isEmpty()).toList();
            }
            if (parsed instanceof Map<?, ?> m && m.get("paths") instanceof List<?> p) {
                return p.stream().map(Object::toString).map(String::strip)
                        .filter(s -> !s.isEmpty()).toList();
            }
        } catch (Exception ignored) {
        }

        // Fallback: newline-delimited paths
        List<String> result = new ArrayList<>();
        for (String line : stripped.split("\n")) {
            String cleaned = line.strip();
            if (cleaned.startsWith("- ")) cleaned = cleaned.substring(2).strip();
            if (!cleaned.isEmpty()) result.add(cleaned);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Python: memory_age_label / memory_freshness_text
    // ------------------------------------------------------------------

    /**
     * Python memory_age_label: "today", "yesterday", or "N days ago".
     */
    public static String memoryAgeLabel(float mtime) {
        long ageSeconds = (long) (Instant.now().getEpochSecond() - mtime);
        int days = (int) (ageSeconds / 86400);
        if (days == 0) return "today";
        if (days == 1) return "yesterday";
        return days + " days ago";
    }

    /**
     * Python memory_freshness_text: staleness warning for older memories.
     */
    public static String memoryFreshnessText(float mtime) {
        long ageSeconds = (long) (Instant.now().getEpochSecond() - mtime);
        int days = (int) (ageSeconds / 86400);
        if (days <= 1) return "";
        return "This memory is " + days + " days old. Memories are point-in-time observations; "
                + "verify claims against the current project state before treating them as facts.";
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Python _apply_selector: if selector provided, use it; otherwise return heuristic.
     */
    private static List<MemoryScanHeader> applySelector(
            String query, List<MemoryScanHeader> headers,
            MemorySelector selector, int maxResults) {
        if (headers.isEmpty() || selector == null) {
            return headers.subList(0, Math.min(maxResults, headers.size()));
        }

        List<String> requested = selector.apply(query, headers);
        Map<String, MemoryScanHeader> byPath = headers.stream()
                .collect(Collectors.toMap(
                        h -> h.relativePath(),
                        h -> h,
                        (a, b) -> a));

        List<MemoryScanHeader> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String path : requested) {
            MemoryScanHeader header = byPath.get(path);
            if (header == null || seen.contains(path)) continue;
            selected.add(header);
            seen.add(path);
        }

        if (selected.size() < maxResults) {
            for (MemoryScanHeader header : headers) {
                if (!seen.contains(header.relativePath())) {
                    selected.add(header);
                    seen.add(header.relativePath());
                    if (selected.size() >= maxResults) break;
                }
            }
        }

        return selected.subList(0, Math.min(maxResults, selected.size()));
    }
}
