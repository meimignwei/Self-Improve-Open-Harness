package com.openharness.common;

import java.util.Optional;
import java.util.concurrent.Flow;

/**
 * Query configuration options.
 * Breaks the circular dependency between openharness-engine and openharness-tools.
 * Java equivalent of Python's QueryOptions.
 */
public record QueryOptions(
        Optional<String> model,
        Optional<String> systemPrompt,
        Optional<Integer> maxTurns,
        Optional<Boolean> streamEnabled,
        Optional<Boolean> toolsEnabled,
        Optional<Boolean> autoCompact,
        Optional<String> workingDirectory,
        Optional<String> sessionId) {

    public static QueryOptions defaults() {
        return new QueryOptions(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    public QueryOptions withModel(String model) {
        return new QueryOptions(Optional.of(model), systemPrompt, maxTurns,
                streamEnabled, toolsEnabled, autoCompact, workingDirectory, sessionId);
    }

    public QueryOptions withMaxTurns(int maxTurns) {
        return new QueryOptions(model, systemPrompt, Optional.of(maxTurns),
                streamEnabled, toolsEnabled, autoCompact, workingDirectory, sessionId);
    }
}
