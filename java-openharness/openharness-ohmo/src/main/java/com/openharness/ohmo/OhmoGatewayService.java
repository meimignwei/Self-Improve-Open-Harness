package com.openharness.ohmo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main gateway service wrapping MessageBus, ChannelManager, RuntimePool, and Bridge.
 * Manages the ohmo foreground/background service lifecycle.
 * Java equivalent of Python ohmo/gateway/service.py.
 */
public class OhmoGatewayService {

    private static final Logger logger = LoggerFactory.getLogger(OhmoGatewayService.class);

    private final MessageBus bus;
    private final ChannelManager channelManager;
    private final OhmoSessionRuntimePool runtimePool;
    private final OhmoGatewayBridge bridge;
    private final GatewayConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private Thread bridgeThread;

    public OhmoGatewayService(String cwd, String workspace) {
        WorkspaceManager wm = new WorkspaceManager();
        Path workspaceRoot = wm.resolve(workspace);
        wm.initialize(workspaceRoot);
        this.config = GatewayConfig.loadFromWorkspace(workspaceRoot);
        this.bus = new MessageBus();
        this.channelManager = new ChannelManager(config, bus);
        this.runtimePool = new OhmoSessionRuntimePool(workspaceRoot, config.providerProfile());
        this.bridge = new OhmoGatewayBridge(bus, runtimePool, workspace);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("Gateway is already running");
            return;
        }

        logger.info("Starting ohmo gateway...");
        channelManager.connectAll();

        bridgeThread = Thread.startVirtualThread(() -> {
            logger.info("Gateway bridge started");
            try {
                bridge.run();
            } catch (Exception e) {
                logger.error("Gateway bridge error", e);
            } finally {
                running.set(false);
                shutdownLatch.countDown();
            }
        });
    }

    public void stop() {
        logger.info("Stopping ohmo gateway...");
        running.set(false);
        bridge.shutdown();
        channelManager.disconnectAll();
        if (bridgeThread != null) {
            bridgeThread.interrupt();
        }
        shutdownLatch.countDown();
    }

    public boolean awaitShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        return shutdownLatch.await(timeout, unit);
    }

    public boolean isRunning() {
        return running.get();
    }

    public GatewayState getState() {
        return running.get()
                ? GatewayState.running(config.providerProfile(),
                        List.copyOf(channelManager.activeChannels()), runtimePool.activeSessions())
                : GatewayState.stopped();
    }

    public void restart() {
        stop();
        start();
    }

    /**
     * Start the gateway and wait for shutdown signal (blocking).
     * Java equivalent of Python gateway foreground mode.
     */
    public void startAndWait() {
        start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
