package com.openharness.extensions.hooks;

import com.openharness.config.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Detects hook configuration changes via file modification time and triggers hot reload.
 * Java equivalent of Python's HookReloader.
 */
public class HookReloader {

    private static final Logger LOG = Logger.getLogger(HookReloader.class.getName());

    private long lastModified;
    private final Path configPath;
    private final HookRegistry registry;

    public HookReloader(Path configPath, HookRegistry registry) {
        this.configPath = configPath;
        this.registry = registry;
        this.lastModified = readLastModified();
    }

    /**
     * Check if the config file has changed and reload if needed.
     */
    public boolean checkAndReload(Settings settings) {
        long currentModified = readLastModified();
        if (currentModified > lastModified) {
            lastModified = currentModified;
            loadFromSettings(settings);
            LOG.info("Hooks reloaded from " + configPath);
            return true;
        }
        return false;
    }

    private void loadFromSettings(Settings settings) {
        registry.clear();
        if (settings.hooks() == null) return;

        for (var hookConfig : settings.hooks()) {
            String type = hookConfig.get("type");
            String matcher = hookConfig.getOrDefault("matcher", "*");
            int timeout = Integer.parseInt(hookConfig.getOrDefault("timeout_seconds", "60"));
            boolean blockOnFailure = Boolean.parseBoolean(hookConfig.getOrDefault("block_on_failure", "false"));
            int priority = Integer.parseInt(hookConfig.getOrDefault("priority", "100"));

            HookDefinition hook = switch (type) {
                case "command" -> new HookDefinition.CommandHook(
                        hookConfig.get("command"), matcher, timeout, blockOnFailure, priority);
                case "http" -> new HookDefinition.HttpHook(
                        hookConfig.get("url"), null, matcher, timeout, blockOnFailure, priority);
                case "prompt" -> new HookDefinition.PromptHook(
                        hookConfig.get("prompt"), hookConfig.get("model"), matcher, timeout, blockOnFailure, priority);
                default -> null;
            };

            if (hook == null) continue;

            String eventName = hookConfig.getOrDefault("event", "*");
            for (HookEvent event : HookEvent.values()) {
                if ("*".equals(eventName) || event.name().equalsIgnoreCase(eventName)) {
                    registry.register(event, hook);
                }
            }
        }
    }

    private long readLastModified() {
        try {
            return Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
