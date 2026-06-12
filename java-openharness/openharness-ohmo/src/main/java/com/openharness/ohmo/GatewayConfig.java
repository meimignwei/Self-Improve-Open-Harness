package com.openharness.ohmo;

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
}
