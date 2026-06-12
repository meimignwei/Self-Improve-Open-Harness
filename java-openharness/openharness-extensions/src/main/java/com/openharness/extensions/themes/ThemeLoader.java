package com.openharness.extensions.themes;

import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.config.Paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads themes: built-in first, then custom from ~/.openharness/themes/.
 * Java equivalent of Python themes/loader.py.
 */
public class ThemeLoader {

    public ThemeConfig load(String themeName) {
        if (BuiltinThemes.ALL.containsKey(themeName)) {
            return BuiltinThemes.ALL.get(themeName);
        }

        Path customPath = Paths.configDir().resolve("themes").resolve(themeName + ".json");
        if (Files.exists(customPath)) {
            try {
                return OpenHarnessObjectMapper.get()
                        .readValue(customPath.toFile(), ThemeConfig.class);
            } catch (IOException e) {
                System.err.println("Failed to load theme: " + themeName + " - " + e.getMessage());
            }
        }

        return BuiltinThemes.DEFAULT;
    }

    public List<ThemeConfig> listAll() {
        List<ThemeConfig> themes = new ArrayList<>(BuiltinThemes.ALL.values());

        Path customDir = Paths.configDir().resolve("themes");
        if (Files.exists(customDir)) {
            try (var files = Files.list(customDir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".json"))
                        .forEach(f -> {
                            try {
                                themes.add(OpenHarnessObjectMapper.get()
                                        .readValue(f.toFile(), ThemeConfig.class));
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }

        return themes;
    }
}
