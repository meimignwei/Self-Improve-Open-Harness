package com.openharness.permissions;

/**
 * Supported permission modes.
 * Java equivalent of Python's PermissionMode enum.
 */
public enum PermissionMode {
    DEFAULT ("default"),
    PLAN ("plan"),
    FULL_AUTO ("full_auto");

    private final String value;

    PermissionMode(String value) { this.value = value; }

    public String value() { return value; }

    public static PermissionMode from(String s) {
        for (var mode : values()) {
            if (mode.value.equals(s)) return mode;
        }
        return DEFAULT;
    }
}
