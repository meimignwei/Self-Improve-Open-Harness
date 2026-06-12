package com.openharness.ohmo;

import java.nio.file.Path;
import java.util.List;

/**
 * Main gateway service: wraps MessageBus, ChannelManager, RuntimePool, and Bridge.
 * Java equivalent of Python ohmo/gateway/service.py.
 */
public class OhmoGatewayService {

    private final MessageBus bus;
    private final ChannelManager channelManager;
    private final OhmoSessionRuntimePool runtimePool;
    private final OhmoGatewayBridge bridge;
    private final GatewayConfig config;
    private volatile boolean running;

    public OhmoGatewayService(String cwd, String workspace) {
        WorkspaceManager wm = new WorkspaceManager();
        Path workspaceRoot = wm.resolve(workspace);
        this.config = GatewayConfig.defaults();
        this.bus = new MessageBus();
        this.channelManager = new ChannelManager(config, bus);
        this.runtimePool = new OhmoSessionRuntimePool(workspaceRoot, config.providerProfile());
        this.bridge = new OhmoGatewayBridge(bus, runtimePool, workspace);
    }

    public void start() {
        channelManager.connectAll();
        Thread.startVirtualThread(() -> {
            running = true;
            bridge.run();
        });
    }

    public void stop() {
        bridge.shutdown();
        channelManager.disconnectAll();
        running = false;
    }

    public GatewayState getState() {
        return running
                ? GatewayState.running(config.providerProfile(),
                        List.copyOf(channelManager.activeChannels()), runtimePool.activeSessions())
                : GatewayState.stopped();
    }
}
