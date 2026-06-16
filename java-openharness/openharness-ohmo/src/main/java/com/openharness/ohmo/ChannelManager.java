package com.openharness.ohmo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages connections to messaging channels (Feishu, Slack, Discord, etc.).
 * Consumes outbound messages from the bus and routes them to the appropriate channel.
 * Java equivalent of Python ohmo/gateway/ ChannelManager + provider_commands.py.
 */
public class ChannelManager {

    private static final Logger logger = LoggerFactory.getLogger(ChannelManager.class);

    private final GatewayConfig config;
    private final MessageBus bus;
    private final Map<String, ChannelConnection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread outboundConsumer;

    public ChannelManager(GatewayConfig config, MessageBus bus) {
        this.config = config;
        this.bus = bus;
    }

    public void connectAll() {
        for (String channel : config.enabledChannels()) {
            try {
                ChannelConnection conn = createConnection(channel);
                conn.connect(bus);
                connections.put(channel, conn);
                logger.info("Channel connected: {}", channel);
            } catch (Exception e) {
                logger.error("Failed to connect channel: {}", channel, e);
            }
        }
        // Start outbound consumer
        if (!connections.isEmpty()) {
            running.set(true);
            outboundConsumer = Thread.startVirtualThread(this::consumeOutbound);
        }
    }

    public void disconnectAll() {
        running.set(false);
        if (outboundConsumer != null) {
            outboundConsumer.interrupt();
            outboundConsumer = null;
        }
        for (var entry : connections.entrySet()) {
            try {
                entry.getValue().disconnect();
                logger.info("Channel disconnected: {}", entry.getKey());
            } catch (Exception e) {
                logger.error("Failed to disconnect: {}", entry.getKey(), e);
            }
        }
        connections.clear();
    }

    public Set<String> activeChannels() {
        return Set.copyOf(connections.keySet());
    }

    /**
     * Consume outbound messages from the bus and send via the target channel.
     */
    private void consumeOutbound() {
        while (running.get()) {
            try {
                MessageBus.OutboundMessage msg = bus.consumeOutbound(Duration.ofSeconds(1));
                if (msg == null) continue;

                ChannelConnection conn = connections.get(msg.channel());
                if (conn == null) {
                    logger.warn("No connection for channel: {}", msg.channel());
                    continue;
                }
                conn.sendMessage(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Outbound consumer error", e);
            }
        }
    }

    ChannelConnection createConnection(String channel) {
        return switch (channel.toLowerCase()) {
            case "feishu" -> new FeishuChannel(config.channelConfigs().get("feishu"));
            case "slack" -> new SlackChannelConnection(config.channelConfigs().get("slack"));
            case "discord" -> new DiscordChannelConnection(config.channelConfigs().get("discord"));
            case "telegram" -> new TelegramChannelConnection(config.channelConfigs().get("telegram"));
            default -> new GenericChannelConnection(channel, config.channelConfigs().get(channel));
        };
    }

    public interface ChannelConnection {
        void connect(MessageBus bus);
        void disconnect();
        void sendMessage(MessageBus.OutboundMessage msg);
    }

    // ------------------------------------------------------------------
    // Stub connections (not yet implemented)
    // ------------------------------------------------------------------

    static class SlackChannelConnection implements ChannelConnection {
        private final Map<String, Object> config;
        SlackChannelConnection(Map<String, Object> config) { this.config = config; }
        @Override public void connect(MessageBus bus) { /* Slack Socket Mode */ }
        @Override public void disconnect() {}
        @Override public void sendMessage(MessageBus.OutboundMessage msg) {}
    }

    static class DiscordChannelConnection implements ChannelConnection {
        private final Map<String, Object> config;
        DiscordChannelConnection(Map<String, Object> config) { this.config = config; }
        @Override public void connect(MessageBus bus) { /* Discord Gateway */ }
        @Override public void disconnect() {}
        @Override public void sendMessage(MessageBus.OutboundMessage msg) {}
    }

    static class TelegramChannelConnection implements ChannelConnection {
        private final Map<String, Object> config;
        TelegramChannelConnection(Map<String, Object> config) { this.config = config; }
        @Override public void connect(MessageBus bus) { /* Telegram Bot API */ }
        @Override public void disconnect() {}
        @Override public void sendMessage(MessageBus.OutboundMessage msg) {}
    }

    static class GenericChannelConnection implements ChannelConnection {
        private final String name;
        private final Map<String, Object> config;
        GenericChannelConnection(String name, Map<String, Object> config) {
            this.name = name; this.config = config;
        }
        @Override public void connect(MessageBus bus) {
            logger.info("Generic channel '{}' connected (no-op)", name);
        }
        @Override public void disconnect() {}
        @Override public void sendMessage(MessageBus.OutboundMessage msg) {}
    }
}
