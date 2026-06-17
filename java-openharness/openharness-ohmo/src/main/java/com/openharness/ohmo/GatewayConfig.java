package com.openharness.ohmo;

import com.openharness.config.AtomicFileWriter;
import com.openharness.common.OpenHarnessObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record GatewayConfig(
        String providerProfile,
        List<String> enabledChannels,
        String sessionRouting,
        boolean sendProgress,
        boolean sendToolHints,
        String permissionMode,
        boolean sandboxEnabled,
        boolean allowRemoteAdminCommands,
        List<String> allowedRemoteAdminCommands,
        String logLevel,
        Map<String, Map<String, Object>> channelConfigs
) {
    public GatewayConfig {
        if (providerProfile == null) providerProfile = "codex";
        if (enabledChannels == null) enabledChannels = List.of();
        if (sessionRouting == null) sessionRouting = "chat-thread";
        if (permissionMode == null) permissionMode = "default";
        if (logLevel == null) logLevel = "info";
        if (allowedRemoteAdminCommands == null) allowedRemoteAdminCommands = List.of("status", "stop");
        if (channelConfigs == null) channelConfigs = Map.of();
    }

    public static GatewayConfig defaults() {
        return new GatewayConfig("codex", List.of(), "chat-thread", true, true,
                "default", false, false, List.of("status", "stop"), "info", Map.of());
    }

    public static GatewayConfig loadFromWorkspace(Path workspaceRoot) {
        Path path = workspaceRoot.resolve("gateway.json");
        if (Files.exists(path)) {
            GatewayConfig config = AtomicFileWriter.readJson(path, GatewayConfig.class);
            return config != null ? config : defaults();
        }
        return defaults();
    }

    public static GatewayConfig loadFromWorkspace(String workspaceRoot) {
        return loadFromWorkspace(Path.of(workspaceRoot));
    }

    public Path saveToWorkspace(Path workspaceRoot) {
        Path path = workspaceRoot.resolve("gateway.json");
        try {
            Files.createDirectories(workspaceRoot);
        } catch (Exception ignored) {}
        AtomicFileWriter.writeJson(path, this);
        return path;
    }

    public GatewayConfig withProviderProfile(String profile) {
        return new GatewayConfig(profile, enabledChannels, sessionRouting, sendProgress,
                sendToolHints, permissionMode, sandboxEnabled, allowRemoteAdminCommands,
                allowedRemoteAdminCommands, logLevel, channelConfigs);
    }

    public GatewayConfig withChannels(List<String> channels, Map<String, Map<String, Object>> configs) {
        return new GatewayConfig(providerProfile, channels, sessionRouting, sendProgress,
                sendToolHints, permissionMode, sandboxEnabled, allowRemoteAdminCommands,
                allowedRemoteAdminCommands, logLevel, configs);
    }

    @SuppressWarnings("unchecked")
    public static GatewayConfig fromMap(Map<String, Object> map) {
        if (map == null) return defaults();
        return new GatewayConfig(
                (String) map.getOrDefault("provider_profile", "codex"),
                (List<String>) map.getOrDefault("enabled_channels", List.of()),
                (String) map.getOrDefault("session_routing", "chat-thread"),
                (boolean) map.getOrDefault("send_progress", true),
                (boolean) map.getOrDefault("send_tool_hints", true),
                (String) map.getOrDefault("permission_mode", "default"),
                (boolean) map.getOrDefault("sandbox_enabled", false),
                (boolean) map.getOrDefault("allow_remote_admin_commands", false),
                (List<String>) map.getOrDefault("allowed_remote_admin_commands", List.of()),
                (String) map.getOrDefault("log_level", "info"),
                (Map<String, Map<String, Object>>) map.getOrDefault("channel_configs", Map.of())
        );
    }
}