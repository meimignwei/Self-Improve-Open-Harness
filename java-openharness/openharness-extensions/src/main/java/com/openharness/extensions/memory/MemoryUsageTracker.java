package com.openharness.extensions.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks memory usage for relevance boosting.
 * Java equivalent of Python memory/usage.py.
 */
public class MemoryUsageTracker {

    private final Map<String, AtomicInteger> usageCounts = new ConcurrentHashMap<>();

    public void recordUsage(String memoryId) {
        usageCounts.computeIfAbsent(memoryId, k -> new AtomicInteger()).incrementAndGet();
    }

    public double computeUsageBoost(String memoryId) {
        AtomicInteger count = usageCounts.get(memoryId);
        if (count == null) return 0.0;
        return Math.min(1.0, count.get() / 10.0);
    }

    public int getUsageCount(String memoryId) {
        AtomicInteger count = usageCounts.get(memoryId);
        return count != null ? count.get() : 0;
    }

    public Map<String, Integer> snapshot() {
        Map<String, Integer> result = new ConcurrentHashMap<>();
        usageCounts.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public void reset(String memoryId) {
        usageCounts.remove(memoryId);
    }

    public void loadFrom(Map<String, Integer> data) {
        usageCounts.clear();
        data.forEach((k, v) -> usageCounts.put(k, new AtomicInteger(v)));
    }
}
