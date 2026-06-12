package com.openharness.ohmo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes inbound messages, routes through session pool, publishes outbound.
 * Java equivalent of Python ohmo/gateway/bridge.py.
 */
public class OhmoGatewayBridge {

    private final MessageBus bus;
    private final OhmoSessionRuntimePool runtimePool;
    private final OhmoSessionRouter router;
    private final String feishuGroupPolicy;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public OhmoGatewayBridge(MessageBus bus, OhmoSessionRuntimePool runtimePool,
                              String workspace) {
        this.bus = bus;
        this.runtimePool = runtimePool;
        this.router = new OhmoSessionRouter();
        this.feishuGroupPolicy = "managed_or_mention";
    }

    public void run() {
        while (running.get()) {
            try {
                MessageBus.InboundMessage msg = bus.consumeInbound(Duration.ofSeconds(5));
                if (msg == null) continue;
                if (!shouldProcess(msg)) continue;

                String sessionKey = router.sessionKeyFor(msg);
                var updates = runtimePool.streamMessage(msg, sessionKey);
                for (var update : updates) {
                    bus.publishOutbound(new MessageBus.OutboundMessage(
                            msg.chatId(), update.text(), msg.channel(), java.util.Map.of()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void shutdown() {
        running.set(false);
    }

    private boolean shouldProcess(MessageBus.InboundMessage msg) {
        if (!"feishu".equals(msg.channel())) return true;
        return switch (feishuGroupPolicy) {
            case "open" -> true;
            case "managed_or_mention" -> msg.isMention();
            default -> msg.isMention();
        };
    }
}
