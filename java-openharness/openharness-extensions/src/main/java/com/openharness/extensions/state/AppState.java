package com.openharness.extensions.state;

import com.openharness.permissions.PermissionMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shared mutable UI/session state.
 * Java equivalent of Python's AppState dataclass.
 */
public record AppState(
        String model,
        String permissionMode,
        String theme,
        Path cwd,
        String provider,
        boolean vimEnabled,
        boolean voiceEnabled,
        boolean voiceAvailable,
        String voiceReason,
        boolean fastMode,
        String effort,
        int passes,
        int mcpConnected,
        int mcpFailed,
        List<BridgeSessionRecord> bridgeSessions,
        String outputStyle,
        Map<String, String> keybindings) {

    public AppState withModel(String newModel) {
        return new AppState(newModel, permissionMode, theme, cwd, provider,
                vimEnabled, voiceEnabled, voiceAvailable, voiceReason, fastMode,
                effort, passes, mcpConnected, mcpFailed, bridgeSessions, outputStyle, keybindings);
    }

    public AppState withTheme(String newTheme) {
        return new AppState(model, permissionMode, newTheme, cwd, provider,
                vimEnabled, voiceEnabled, voiceAvailable, voiceReason, fastMode,
                effort, passes, mcpConnected, mcpFailed, bridgeSessions, outputStyle, keybindings);
    }

    public AppState withVoiceEnabled(boolean v) {
        return new AppState(model, permissionMode, theme, cwd, provider,
                vimEnabled, v, voiceAvailable, voiceReason, fastMode,
                effort, passes, mcpConnected, mcpFailed, bridgeSessions, outputStyle, keybindings);
    }

    public AppState withPermissionMode(String newMode) {
        return new AppState(model, newMode, theme, cwd, provider,
                vimEnabled, voiceEnabled, voiceAvailable, voiceReason, fastMode,
                effort, passes, mcpConnected, mcpFailed, bridgeSessions, outputStyle, keybindings);
    }

    public static AppState defaults(Path cwd) {
        return new AppState("claude-sonnet-4-6", "default", "default", cwd,
                "", false, false, false, "not initialized", false, "medium", 1,
                0, 0, List.of(), "tui", Map.of());
    }

    public enum OutputStyle { TUI, BACKEND, PRINT }

    public record BridgeSessionRecord(String sessionId, Path cwd, java.time.Instant startedAt) {}
}
