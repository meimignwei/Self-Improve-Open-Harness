package com.openharness.extensions.hooks;

import java.util.Map;

/**
 * Result of a single hook execution.
 * Java equivalent of Python's HookResult dataclass.
 */
public record HookResult(
        String hookType,
        boolean success,
        boolean blocked,
        String output,
        String reason,
        Map<String, Object> metadata) {

    public HookResult(boolean success, boolean blocked, String output, Map<String, Object> metadata) {
        this("command", success, blocked, output, output, metadata);
    }

    public static HookResult ok(String output) {
        return new HookResult("command", true, false, output, "", Map.of());
    }

    public static HookResult blocked(String reason) {
        return new HookResult("command", false, true, reason, reason, Map.of());
    }

    public static HookResult failed(String error) {
        return new HookResult("command", false, false, error, error, Map.of());
    }
}
