package com.openharness.extensions.memory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple heuristic memory search matching Python memory/search.py.
 *
 * Scoring: metadata hits ×2 + body hits ×1 + importance ×0.4 + min(use_count,5) ×0.1 + recency_boost.
 * Recency: ≤14 days → 0.3, ≤30 days → 0.1.
 */
public class MemorySearch {

    /** ASCII word pattern (letters, digits, underscores). */
    private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z0-9_]+");
    /** Han ideograph pattern (CJK Unified + Extension A). */
    private static final Pattern HAN = Pattern.compile("[\\u4e00-\\u9fff\\u3400-\\u4dbf]");

    private final MemoryUsageTracker usageTracker;

    public MemorySearch(MemoryUsageTracker usageTracker) {
        this.usageTracker = usageTracker;
    }

    public MemorySearch() {
        this.usageTracker = null;
    }

    /**
     * Python find_relevant_memories: tokenize → score → sort → limit.
     */
    public List<MemoryScanHeader> findRelevantMemories(String query, Path memoryDir,
                                                        int maxResults) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) return List.of();

        List<MemoryScanHeader> headers = MemoryScanner.scanMemoryFiles(memoryDir, 100);
        List<ScoredHeader> scored = new ArrayList<>();

        for (MemoryScanHeader header : headers) {
            String meta = (header.title() + " " + header.description()).toLowerCase();
            String body = header.bodyPreview().toLowerCase();

            int metaHits = countMatches(tokens, meta);
            int bodyHits = countMatches(tokens, body);

            int useCount = usageTracker != null ? usageTracker.getUsageCount(header.id()) : 0;
            double score = metaHits * 2.0
                    + bodyHits
                    + header.importance() * 0.4
                    + Math.min(useCount, 5) * 0.1
                    + recencyBoost(header);

            if (metaHits > 0 || bodyHits > 0) {
                scored.add(new ScoredHeader(score, header));
            }
        }

        scored.sort(Comparator.comparing(ScoredHeader::score).reversed()
                .thenComparing(sh -> sh.header.modifiedAt(), Comparator.reverseOrder()));

        return scored.stream()
                .limit(maxResults)
                .map(ScoredHeader::header)
                .toList();
    }

    /**
     * Python _tokenize: ASCII 3+ char words + Han ideographs.
     */
    public static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) return tokens;

        String lower = text.toLowerCase();

        // ASCII word tokens (3+ chars)
        Matcher wordMatcher = ASCII_WORD.matcher(lower);
        while (wordMatcher.find()) {
            String token = wordMatcher.group();
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }

        // Han ideographs (each character carries independent meaning)
        Matcher hanMatcher = HAN.matcher(text);
        while (hanMatcher.find()) {
            tokens.add(hanMatcher.group());
        }

        return tokens;
    }

    /**
     * Python _recency_boost: ≤14d → 0.3, ≤30d → 0.1, else 0.
     */
    public static double recencyBoost(MemoryScanHeader header) {
        // Use updated_at first, then created_at
        Instant timestamp = header.header().updatedAt() != null
                ? header.header().updatedAt() : header.header().createdAt();
        if (timestamp == null) return 0.0;

        long days = Duration.between(timestamp, Instant.now()).toDays();
        if (days <= 14) return 0.3;
        if (days <= 30) return 0.1;
        return 0.0;
    }

    private static int countMatches(Set<String> tokens, String text) {
        int hits = 0;
        for (String token : tokens) {
            if (text.contains(token)) hits++;
        }
        return hits;
    }

    // ── Legacy API compatibility ──

    public List<MemoryEntry.ScoredMemory> search(String query, List<MemoryEntry> memories, int topK) {
        Set<String> tokens = tokenize(query);
        return memories.stream()
                .filter(m -> !m.header().disabled() && !m.header().isExpired())
                .map(memory -> {
                    String meta = (memory.header().name() + " " + memory.header().description()).toLowerCase();
                    String body = memory.body() != null ? memory.body().toLowerCase() : "";

                    int metaHits = countMatches(tokens, meta);
                    int bodyHits = countMatches(tokens, body);
                    int useCount = usageTracker != null ? usageTracker.getUsageCount(memory.header().id()) : 0;

                    double score = metaHits * 2.0
                            + bodyHits
                            + memory.header().importance() * 0.4
                            + Math.min(useCount, 5) * 0.1
                            + computeRecencyBoostOld(memory.header().updatedAt());

                    return new MemoryEntry.ScoredMemory(memory, score);
                })
                .filter(sm -> {
                    // Python: only include entries with metadata or body hits
                    Set<String> t = tokenize(query);
                    if (t.isEmpty()) return true;
                    String meta = (sm.memory().header().name() + " "
                            + sm.memory().header().description()).toLowerCase();
                    String body = sm.memory().body() != null ? sm.memory().body().toLowerCase() : "";
                    return countMatches(t, meta) > 0 || countMatches(t, body) > 0;
                })
                .sorted(Comparator.comparing(MemoryEntry.ScoredMemory::score).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * Legacy recency boost — exp(-days/30).
     */
    double computeRecencyBoostOld(Instant updatedAt) {
        if (updatedAt == null) return 0.0;
        long days = Duration.between(updatedAt, Instant.now()).toDays();
        return Math.exp(-days / 30.0);
    }

    private record ScoredHeader(double score, MemoryScanHeader header) {}
}
