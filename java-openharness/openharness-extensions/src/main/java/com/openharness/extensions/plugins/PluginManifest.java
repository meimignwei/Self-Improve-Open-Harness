package com.openharness.extensions.plugins;

import java.nio.file.Path;
import java.util.List;

/**
 * Plugin manifest from plugin.json or .claude-plugin/plugin.json.
 * Java equivalent of Python's PluginManifest Pydantic model.
 */
public record PluginManifest(
        String name,
        String version,
        String description,
        String author,
        String license,
        List<String> keywords,
        Path pluginDir,
        String entryPoint,
        String commandsDir,
        String agentsDir,
        String skillsDir,
        String hooksDir,
        String mcpServersDir,
        List<String> dependsOn) {

    public PluginManifest {
        if (keywords == null) keywords = List.of();
        if (dependsOn == null) dependsOn = List.of();
    }
}
