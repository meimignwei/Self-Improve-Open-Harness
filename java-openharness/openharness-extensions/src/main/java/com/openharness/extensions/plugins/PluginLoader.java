package com.openharness.extensions.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.config.Paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Discovers and loads plugins from user and project directories.
 * Java equivalent of Python's PluginLoader.
 */
public class PluginLoader {

    private static final Logger LOG = Logger.getLogger(PluginLoader.class.getName());
    private static final String PLUGIN_JSON = "plugin.json";
    private static final String CLAUDE_PLUGIN_JSON = ".claude-plugin/plugin.json";

    private final ObjectMapper mapper;

    public PluginLoader() {
        this.mapper = OpenHarnessObjectMapper.get();
    }

    /**
     * Load all plugins from user dir, project dir, and enabled plugin paths.
     */
    public List<LoadedPlugin> loadAll(Path cwd, java.util.Map<String, Boolean> enabledPlugins) {
        List<LoadedPlugin> plugins = new ArrayList<>();

        // 1. User plugins (~/.openharness/plugins/)
        Path userPluginsDir = Paths.homePluginsDir();
        if (Files.isDirectory(userPluginsDir)) {
            loadFromDir(plugins, userPluginsDir, "user");
        }

        // 2. Project plugins (.openharness/plugins/)
        Path projectPluginsDir = cwd.resolve(".openharness/plugins");
        if (Files.isDirectory(projectPluginsDir)) {
            loadFromDir(plugins, projectPluginsDir, "project");
        }

        return plugins;
    }

    private void loadFromDir(List<LoadedPlugin> plugins, Path dir, String source) {
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(pluginDir -> {
                try {
                    PluginManifest manifest = loadManifest(pluginDir);
                    if (manifest != null) {
                        plugins.add(new LoadedPlugin(manifest, source));
                        LOG.fine("Loaded plugin: " + manifest.name() + " from " + source);
                    }
                } catch (IOException e) {
                    LOG.warning("Failed to load plugin from " + pluginDir + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.fine("Plugin directory not found or not readable: " + dir);
        }
    }

    /**
     * Load plugin manifest from plugin.json or .claude-plugin/plugin.json.
     */
    public PluginManifest loadManifest(Path pluginDir) throws IOException {
        Path manifestPath = pluginDir.resolve(PLUGIN_JSON);
        if (!Files.exists(manifestPath)) {
            manifestPath = pluginDir.resolve(CLAUDE_PLUGIN_JSON);
        }
        if (!Files.exists(manifestPath)) return null;

        String json = Files.readString(manifestPath);
        return mapper.readValue(json, PluginManifest.class);
    }

    public record LoadedPlugin(PluginManifest manifest, String source) {
        public String name() { return manifest.name(); }
        public Path skillsDir() {
            if (manifest.skillsDir() != null) {
                return manifest.pluginDir().resolve(manifest.skillsDir());
            }
            return null;
        }
        public List<com.openharness.extensions.skills.SkillDefinition> skills() { return List.of(); }
        public List<String> agents() { return List.of(); }
    }
}
