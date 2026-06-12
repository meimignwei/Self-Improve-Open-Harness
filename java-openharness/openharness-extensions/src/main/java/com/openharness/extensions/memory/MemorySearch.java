package com.openharness.extensions.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Heuristic relevance scoring: metadata 2x + body BM25 + importance + recency half-life + usage boost.
 * Java equivalent of Python memory/search.py and memory/relevance.py.
 */
public class MemorySearch {

    private final MemoryUsageTracker usageTracker;

    public MemorySearch(MemoryUsageTracker usageTracker) {
        this.usageTracker = usageTracker;
    }

    public MemorySearch() {
        this.usageTracker = null;
    }

    public List<MemoryEntry.ScoredMemory> search(String query, List<MemoryEntry> memories, int topK) {
        Map<String, Double> bm25Cache = new HashMap<>();
        return memories.stream()
                .filter(m -> !m.header().disabled() && !m.isExpired())
                .map(memory -> new MemoryEntry.ScoredMemory(memory, score(query, memory, bm25Cache)))
                .sorted(Comparator.comparing(MemoryEntry.ScoredMemory::score).reversed())
                .limit(topK)
                .toList();
    }

    double score(String query, MemoryEntry memory, Map<String, Double> bm25Cache) {
        String queryLower = query.toLowerCase();

        double metadataScore = computeMetadataMatch(queryLower, memory.header()) * 2.0;
        double bodyScore = computeBodyBM25(queryLower, memory.body(), bm25Cache);
        double importanceBoost = memory.header().importance() / 10.0;
        double recencyBoost = computeRecencyBoost(memory.header().updatedAt());
        double usageBoost = usageTracker != null
                ? usageTracker.computeUsageBoost(memory.header().id())
                : 0.0;

        return metadataScore + bodyScore + importanceBoost + recencyBoost + usageBoost;
    }

    private double computeMetadataMatch(String query, MemoryEntry.MemoryHeader header) {
        double score = 0.0;
        score += fuzzyMatch(header.name(), query);
        score += fuzzyMatch(header.description(), query);
        return score;
    }

    private double fuzzyMatch(String text, String query) {
        if (text == null || text.isEmpty()) return 0.0;
        String textLower = text.toLowerCase();

        if (textLower.contains(query)) return 1.0;

        String[] queryWords = query.split("\\s+");
        int matchCount = 0;
        for (String word : queryWords) {
            if (word.length() > 2 && textLower.contains(word)) {
                matchCount++;
            }
        }
        return queryWords.length > 0 ? (double) matchCount / queryWords.length : 0.0;
    }

    private double computeBodyBM25(String query, String body, Map<String, Double> cache) {
        if (body == null || body.isEmpty()) return 0.0;

        String key = body.hashCode() + "::" + query;
        Double cached = cache.get(key);
        if (cached != null) return cached;

        String bodyLower = body.toLowerCase();
        String[] queryTerms = query.split("\\s+");
        double k1 = 1.5;
        double b = 0.75;
        double avgDocLength = 500.0;

        double docLength = bodyLower.length();
        double score = 0.0;

        for (String term : queryTerms) {
            if (term.length() < 2) continue;
            int tf = countTerm(bodyLower, term);
            if (tf == 0) continue;

            double idf = 1.0;
            double tfComponent = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * docLength / avgDocLength));
            score += idf * tfComponent;
        }

        score = score / (1 + score);
        cache.put(key, score);
        return score;
    }

    private int countTerm(String text, String term) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }

    /**
     * 30-day half-life for recency: exp(-days/30).
     */
    double computeRecencyBoost(Instant updatedAt) {
        if (updatedAt == null) return 0.0;
        long days = Duration.between(updatedAt, Instant.now()).toDays();
        return Math.exp(-days / 30.0);
    }
}
