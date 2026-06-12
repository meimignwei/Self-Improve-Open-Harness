package com.openharness.ohmo;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Async bidirectional message bus connecting channels to the session runtime pool.
 * Java equivalent of Python ohmo/gateway/ MessageBus.
 */
public class MessageBus {

    private final BlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();

    public void publishInbound(InboundMessage msg) {
        inbound.add(msg);
    }

    public InboundMessage consumeInbound(Duration timeout) throws InterruptedException {
        return inbound.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public InboundMessage consumeInbound() throws InterruptedException {
        return inbound.take();
    }

    public void publishOutbound(OutboundMessage msg) {
        outbound.add(msg);
    }

    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outbound.take();
    }

    public OutboundMessage consumeOutbound(Duration timeout) throws InterruptedException {
        return outbound.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public int inboundSize() { return inbound.size(); }
    public int outboundSize() { return outbound.size(); }

    public record InboundMessage(
            String channel, String chatId, String senderId, String content,
            boolean isMention, java.util.Map<String, Object> metadata,
            String sessionKeyOverride
    ) {
        public InboundMessage {
            if (metadata == null) metadata = java.util.Map.of();
        }
    }

    public record OutboundMessage(
            String chatId, String text, String channel,
            java.util.Map<String, Object> metadata
    ) {
        public OutboundMessage {
            if (metadata == null) metadata = java.util.Map.of();
        }
    }
}
