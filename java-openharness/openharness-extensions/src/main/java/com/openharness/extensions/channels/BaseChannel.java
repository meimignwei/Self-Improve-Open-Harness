package com.openharness.extensions.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Abstract channel base with message bus and ACL support.
 * Java equivalent of Python channels/ BaseChannel.
 */
public abstract class BaseChannel {

    protected static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();
    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    protected final BlockingQueue<ChannelMessage> inbound = new LinkedBlockingQueue<>();
    protected final BlockingQueue<ChannelMessage> outbound = new LinkedBlockingQueue<>();
    protected final Set<String> allowFrom;
    protected final Map<String, Object> config;
    protected volatile boolean running;

    protected BaseChannel(Map<String, Object> config) {
        this.config = config != null ? new ConcurrentHashMap<>(config) : new ConcurrentHashMap<>();
        @SuppressWarnings("unchecked")
        var allowed = (Set<String>) this.config.getOrDefault("allow_from", Set.of());
        this.allowFrom = ConcurrentHashMap.newKeySet();
        this.allowFrom.addAll(allowed);
    }

    public abstract String getChannelType();

    public abstract void start();

    public abstract void stop();

    public abstract void send(ChannelMessage message);

    public ChannelMessage receive() throws InterruptedException {
        return inbound.take();
    }

    public boolean isRunning() { return running; }

    protected boolean isAllowed(String senderId) {
        return allowFrom.isEmpty() || allowFrom.contains(senderId);
    }

    public record ChannelMessage(
            String channelType, String chatId, String senderId,
            String content, boolean isMention, Map<String, Object> metadata
    ) {
        public ChannelMessage {
            if (metadata == null) metadata = Map.of();
        }

        public static ChannelMessage of(String channel, String chatId, String senderId, String content) {
            return new ChannelMessage(channel, chatId, senderId, content, false, Map.of());
        }
    }
}
