package com.openharness.ui;

/**
 * Creates the appropriate RuntimeOutput based on mode.
 * Java equivalent of Python ui/runtime.py RuntimeFactory.
 */
public final class RuntimeFactory {

    private RuntimeFactory() {}

    public static RuntimeOutput create(RuntimeOutput.Mode mode) {
        return switch (mode) {
            case BACKEND -> new BackendOutput();
            case PRINT -> new PrintOutput();
            case TUI -> new PrintOutput(); // TUI requires JLine — fallback to print
        };
    }

    public static RuntimeOutput.Mode resolveMode(String outputStyle) {
        if (outputStyle == null) return RuntimeOutput.Mode.PRINT;
        return switch (outputStyle.toLowerCase()) {
            case "backend", "json", "stream-json" -> RuntimeOutput.Mode.BACKEND;
            case "print", "text" -> RuntimeOutput.Mode.PRINT;
            case "tui" -> RuntimeOutput.Mode.TUI;
            default -> RuntimeOutput.Mode.PRINT;
        };
    }
}
