package com.openharness.extensions.keybindings;

import java.util.Map;

/**
 * Default keybinding configuration.
 * Java equivalent of Python keybindings/default_bindings.py.
 */
public final class DefaultKeybindings {

    public static final Map<String, String> DEFAULTS = Map.of(
            "ctrl+l", "clear",
            "ctrl+k", "toggle_vim",
            "ctrl+v", "toggle_voice",
            "ctrl+t", "tasks"
    );

    private DefaultKeybindings() {}
}
