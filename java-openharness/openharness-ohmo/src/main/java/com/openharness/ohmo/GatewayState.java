package com.openharness.ohmo;

import java.util.List;

public record GatewayState(
        boolean running,
        Long pid,
        int activeSessions,
        String providerProfile,
        List<String> enabledChannels,
        String lastError
) {
    public static GatewayState stopped() {
        return new GatewayState(false, null, 0, null, List.of(), null);
    }

    public static GatewayState running(String profile, List<String> channels, int sessions) {
        return new GatewayState(true, ProcessHandle.current().pid(), sessions, profile, channels, null);
    }
}
