package com.openharness.extensions.hooks;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Aggregated result for a hook event.
 * Java equivalent of Python's AggregatedHookResult dataclass.
 */
public record AggregatedHookResult(List<HookResult> results) {

    public boolean blocked() {
        return results.stream().anyMatch(HookResult::blocked);
    }

    public String reason() {
        return results.stream()
                .filter(HookResult::blocked)
                .map(r -> !r.reason().isEmpty() ? r.reason() : r.output())
                .collect(Collectors.joining("; "));
    }
}
