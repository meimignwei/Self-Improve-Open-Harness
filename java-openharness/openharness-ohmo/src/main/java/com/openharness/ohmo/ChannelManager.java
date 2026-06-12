package com.openharness.ohmo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages connections to messaging channels (Feishu, Slack, Discord, etc.).
 * Java equivalent of Python ohmo/gateway/ ChannelManager + provider_commands.py.
 */
public class ChannelManager {

    private final GatewayConfig config;
    private final MessageBus bus;
    private final Map<String, ChannelConnection> connections = new ConcurrentHashMap<>();

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
            } catch (Exception e) {
                System.err.println("Failed to connect channel: " + channel + " - " + e.getMessage());
            }
        }
    }

    public void disconnectAll() {
        for (var entry : connections.entrySet()) {
            try {
                entry.getValue().disconnect();
            } catch (Exception e) {
                System.err.println("Failed to disconnect: " + entry.getKey());
            }
        }
        connections.clear();
    }

    public Set<String> activeChannels() {
        return Set.copyOf(connections.keySet());
    }

    ChannelConnection createConnection(String channel) {
        return switch (channel.toLowerCase()) {
            case "feishu" -> new FeishuChannelConnection(config.channelConfigs().get("feishu"));
            case "slack" -> new SlackChannelConnection(config.channelConfigs().get("slack"));
            case "discord" -> new DiscordChannelConnection(config.channelConfigs().get("discord"));
            case "telegram" -> new TelegramChannelConnection(config.channelConfigs().get("telegram"));
            default -> new GenericChannelConnection(channel, config.channelConfigs().get(channel));
        };
    }

    interface ChannelConnection {
        void connect(MessageBus bus);
        void disconnect();
    }

    static class FeishuChannelConnection implements ChannelConnection {
        private final Map<String, Object> config;
        FeishuChannelConnection(Map<String, Object> config) { this.config = config; }
        @Override public void connect(MessageBus bus) { /* Feishu WebSocket SDK */ }
        @Override public void disconnect() {}
    }

    static class SlackChannelConnection implements ChannelConnection {
        private final Map<String, Object> config;
        SlackChannelConnection(Map<String, Object> config) { this.config = config; }
        @Override public void connect(MessageBus bus) { /* Slack Socket Mode */ }
        @Override public void disconnect() {}
    }

    static class DiscordChannelConnection implements ChannelConnection {
        private final Map<String, Object> config;
        DiscordChannelConnection(Map<String, Object> config) { this.config = config; }
        @Override public void connect(MessageBus bus) { /* Discord Gateway */ }
        @Override public void disconnect() {}
    }

    static class TelegramChannelConnection implements ChannelConnection {
        private final Map<String, Object> config;
        TelegramChannelConnection(Map<String, Object> config) { this.config = config; }
        @Override public void connect(MessageBus bus) { /* Telegram Bot API */ }
        @Override public void disconnect() {}
    }

    static class GenericChannelConnection implements ChannelConnection {
        private final String name;
        private final Map<String, Object> config;
        GenericChannelConnection(String name, Map<String, Object> config) {
            this.name = name; this.config = config;
        }
        @Override public void connect(MessageBus bus) {}
        @Override public void disconnect() {}
    }
}
