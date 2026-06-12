package com.openharness.extensions.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result of a single hook execution.
 * Java equivalent of Python's HookResult class.
 */
public record HookResult(
        boolean success,
        boolean blocked,
        String message,
        Map<String, Object> metadata) {

    public static HookResult ok(String message) {
        return new HookResult(true, false, message, Map.of());
    }

    public static HookResult blocked(String reason) {
        return new HookResult(false, true, reason, Map.of());
    }

    public static HookResult failed(String error) {
        return new HookResult(false, false, error, Map.of());
    }
}
