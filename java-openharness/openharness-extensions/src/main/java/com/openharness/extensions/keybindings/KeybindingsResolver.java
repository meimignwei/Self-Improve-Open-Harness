package com.openharness.extensions.keybindings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves keybindings with user overrides.
 * Java equivalent of Python keybindings/loader.py and resolver.py.
 */
public class KeybindingsResolver {

    private static final Set<String> KNOWN_ACTIONS = Set.of(
            "clear", "toggle_vim", "toggle_voice",
            "tasks", "help", "compact", "exit", "model");

    public Map<String, String> resolve(Path userConfigPath) {
        Map<String, String> merged = new HashMap<>(DefaultKeybindings.DEFAULTS);

        if (userConfigPath != null && Files.exists(userConfigPath)) {
            try {
                Map<String, String> userBindings = OpenHarnessObjectMapper.get()
                        .readValue(userConfigPath.toFile(),
                                new TypeReference<Map<String, String>>() {});
                merged.putAll(userBindings);
            } catch (IOException e) {
                System.err.println("Failed to load keybindings: " + e.getMessage());
            }
        }

        for (var entry : merged.entrySet()) {
            if (!KNOWN_ACTIONS.contains(entry.getValue())) {
                System.err.println("Unknown keybinding action: "
                        + entry.getKey() + " -> " + entry.getValue());
            }
        }

        return Map.copyOf(merged);
    }
}
