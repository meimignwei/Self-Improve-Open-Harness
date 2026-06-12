package com.openharness.ohmo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chat/thread session runtime pool with session resume support.
 * Java equivalent of Python ohmo/gateway/runtime.py.
 */
public class OhmoSessionRuntimePool {

    private final Map<String, RuntimeBundle> bundles = new ConcurrentHashMap<>();
    private final String providerProfile;
    private final Path workspaceRoot;

    public OhmoSessionRuntimePool(Path workspaceRoot, String providerProfile) {
        this.workspaceRoot = workspaceRoot;
        this.providerProfile = providerProfile;
    }

    public RuntimeBundle getBundle(String sessionKey, String userPrompt, Path cwd) {
        return bundles.compute(sessionKey, (key, existing) -> {
            if (existing != null && existing.cwd().equals(cwd)) {
                return existing;
            }
            RuntimeBundle bundle = new RuntimeBundle(
                    sessionKey, cwd, providerProfile, workspaceRoot);
            bundle.initialize(userPrompt);
            return bundle;
        });
    }

    public List<GatewayStreamUpdate> streamMessage(MessageBus.InboundMessage msg, String sessionKey) {
        Path cwd = Path.of(System.getProperty("user.dir"));
        RuntimeBundle bundle = getBundle(sessionKey, msg.content(), cwd);
        return List.of(new GatewayStreamUpdate(msg.content(), "ok", "processed"));
    }

    public int activeSessions() {
        return bundles.size();
    }

    public record GatewayStreamUpdate(String text, String status, String sessionKey) {}
}
