package com.openharness.common;

/**
 * Token usage snapshot for cost tracking.
 * Java equivalent of Python's UsageSnapshot.
 */
public record UsageSnapshot(int inputTokens, int outputTokens) {

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
