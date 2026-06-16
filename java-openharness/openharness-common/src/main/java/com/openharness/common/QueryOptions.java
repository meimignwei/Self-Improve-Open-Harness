package com.openharness.common;

import java.util.List;
import java.util.Optional;

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
        Optional<String> sessionId,
        Optional<List<String>> allowedTools) {

    public static QueryOptions defaults() {
        return new QueryOptions(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public QueryOptions withModel(String model) {
        return new QueryOptions(Optional.of(model), systemPrompt, maxTurns,
                streamEnabled, toolsEnabled, autoCompact, workingDirectory, sessionId, allowedTools);
    }

    public QueryOptions withMaxTurns(int maxTurns) {
        return new QueryOptions(model, systemPrompt, Optional.of(maxTurns),
                streamEnabled, toolsEnabled, autoCompact, workingDirectory, sessionId, allowedTools);
    }

    public QueryOptions withSystemPrompt(String systemPrompt) {
        return new QueryOptions(model, Optional.ofNullable(systemPrompt), maxTurns,
                streamEnabled, toolsEnabled, autoCompact, workingDirectory, sessionId, allowedTools);
    }

    public QueryOptions withAllowedTools(List<String> allowedTools) {
        return new QueryOptions(model, systemPrompt, maxTurns,
                streamEnabled, toolsEnabled, autoCompact, workingDirectory, sessionId,
                Optional.ofNullable(allowedTools));
    }
}
