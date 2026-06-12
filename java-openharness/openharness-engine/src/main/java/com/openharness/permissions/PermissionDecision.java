package com.openharness.permissions;

/**
 * Result of checking whether a tool invocation may run.
 * Java equivalent of Python's PermissionDecision dataclass.
 */
public record PermissionDecision(
        boolean allowed,
        boolean requiresConfirmation,
        String reason) {

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(true, false, reason);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, false, reason);
    }

    public static PermissionDecision confirm(String reason) {
        return new PermissionDecision(false, true, reason);
    }
}
