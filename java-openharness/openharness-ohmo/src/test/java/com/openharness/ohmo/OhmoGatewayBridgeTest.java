package com.openharness.ohmo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OhmoGatewayBridgeTest {

    @TempDir
    Path tempDir;

    private Path workspaceRoot;
    private MessageBus bus;
    private OhmoSessionRuntimePool pool;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspace");
        new WorkspaceManager().initialize(workspaceRoot);
        bus = new MessageBus();
        pool = new OhmoSessionRuntimePool(workspaceRoot, "claude", null, null);
    }

    @Test
    void constructShouldNotThrow() {
        var bridge = new OhmoGatewayBridge(bus, pool, workspaceRoot.toString());
        assertNotNull(bridge);
    }

    @Test
    void shutdownShouldCleanUp() {
        var bridge = new OhmoGatewayBridge(bus, pool, workspaceRoot.toString());
        assertDoesNotThrow(bridge::shutdown);
    }

    @Test
    void messageBusShouldReceiveInboundMessages() throws Exception {
        var msg = new MessageBus.InboundMessage(
                "feishu", "chat-1", "user-1", "Hello", false, Map.of(), null);
        bus.publishInbound(msg);

        assertEquals(1, bus.inboundSize());
        var consumed = bus.consumeInbound(Duration.ofMillis(100));
        assertNotNull(consumed);
        assertEquals("Hello", consumed.content());
    }

    @Test
    void messageBusShouldReceiveOutboundMessages() {
        var msg = new MessageBus.OutboundMessage(
                "chat-1", "Response", "feishu", Map.of());
        bus.publishOutbound(msg);

        assertEquals(1, bus.outboundSize());
    }
}
