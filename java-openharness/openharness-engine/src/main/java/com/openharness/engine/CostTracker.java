package com.openharness.engine;

import com.openharness.common.UsageSnapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks accumulated LLM API cost across agent loop turns.
 * Maps provider+model to per-1K-token pricing.
 */
public class CostTracker {

    // Pricing table: USD per 1K tokens (input / output)
    private static final Map<String, double[]> PRICING = Map.ofEntries(
            Map.entry("claude-sonnet-4-6", new double[]{0.003, 0.015}),
            Map.entry("claude-opus-4-7", new double[]{0.015, 0.075}),
            Map.entry("claude-haiku-4-5", new double[]{0.0008, 0.004}),
            Map.entry("gpt-4o", new double[]{0.005, 0.015}),
            Map.entry("gpt-4o-mini", new double[]{0.00015, 0.0006}),
            Map.entry("deepseek-chat", new double[]{0.00027, 0.0011}),
            Map.entry("gemini-pro", new double[]{0.0005, 0.0015}),
            Map.entry("grok-2", new double[]{0.002, 0.010}),
            Map.entry("qwen-plus", new double[]{0.0008, 0.002}),
            Map.entry("moonshot-v1-8k", new double[]{0.001, 0.002}),
            Map.entry("llama-3.1-70b-versatile", new double[]{0.0009, 0.0009})
    );

    private final ConcurrentHashMap<String, UsageSnapshot> usageByModel = new ConcurrentHashMap<>();
    private double totalCost = 0.0;

    public void add(UsageSnapshot usage, String model) {
        if (usage == null) return;
        usageByModel.merge(model, usage, (a, b) ->
                new UsageSnapshot(a.inputTokens() + b.inputTokens(),
                        a.outputTokens() + b.outputTokens()));
        totalCost += computeCost(usage, model);
    }

    public double getTotalCost() {
        return totalCost;
    }

    public int getTotalTokens() {
        return usageByModel.values().stream()
                .mapToInt(UsageSnapshot::totalTokens)
                .sum();
    }

    public Map<String, UsageSnapshot> getUsageByModel() {
        return Map.copyOf(usageByModel);
    }

    public void reset() {
        usageByModel.clear();
        totalCost = 0.0;
    }

    static double computeCost(UsageSnapshot usage, String model) {
        double[] rates = PRICING.getOrDefault(model, new double[]{0.0, 0.0});
        return (usage.inputTokens() * rates[0] + usage.outputTokens() * rates[1]) / 1000.0;
    }

    static Map<String, double[]> pricingTable() {
        return Map.copyOf(PRICING);
    }
}
