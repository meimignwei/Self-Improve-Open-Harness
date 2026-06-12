package com.openharness.extensions.channels;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BaseChannelTest {

    private TestChannel channel;

    // Minimal concrete implementation for testing BaseChannel
    static class TestChannel extends BaseChannel {
        TestChannel(Map<String, Object> config) {
            super(config);
        }

        @Override public String getChannelType() { return "test"; }
        @Override public void start() { running = true; }
        @Override public void stop() { running = false; }
        @Override public void send(ChannelMessage message) { outbound.add(message); }
    }

    @BeforeEach
    void setUp() {
        channel = new TestChannel(Map.of());
    }

    @Test
    void channelTypeShouldMatch() {
        assertEquals("test", channel.getChannelType());
    }

    @Test
    void startShouldSetRunning() {
        assertFalse(channel.isRunning());
        channel.start();
        assertTrue(channel.isRunning());
    }

    @Test
    void stopShouldClearRunning() {
        channel.start();
        channel.stop();
        assertFalse(channel.isRunning());
    }

    @Test
    void sendShouldAddToOutbound() {
        var msg = BaseChannel.ChannelMessage.of("test", "chat1", "user1", "hello");
        channel.send(msg);
        assertEquals("hello", channel.outbound.poll().content());
    }

    @Test
    void receiveShouldBlockOnEmptyQueue() {
        assertThrows(InterruptedException.class, () -> {
            // poll with timeout - inbound is empty so will wait
            Thread.currentThread().interrupt();
            channel.receive();
        });
    }

    @Test
    void isAllowedShouldReturnTrueWhenAllowFromEmpty() {
        assertTrue(channel.isAllowed("anyone"));
    }

    @Test
    void isAllowedShouldFilterWhenAllowFromSet() {
        var restricted = new TestChannel(Map.of("allow_from", Set.of("user-a", "user-b")));
        assertTrue(restricted.isAllowed("user-a"));
        assertFalse(restricted.isAllowed("user-c"));
    }

    @Test
    void channelMessageShouldDefaultMetadataToEmpty() {
        var msg = new BaseChannel.ChannelMessage("slack", "ch1", "u1", "text", false, null);
        assertTrue(msg.metadata().isEmpty());
    }

    @Test
    void channelMessageOfShouldDefaultIsMentionFalse() {
        var msg = BaseChannel.ChannelMessage.of("feishu", "c1", "u1", "hi");
        assertFalse(msg.isMention());
        assertTrue(msg.metadata().isEmpty());
    }

    @Test
    void channelMessageShouldStoreAllFields() {
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map) Map.of("chat_type", "channel");
        var msg = new BaseChannel.ChannelMessage("slack", "C123", "U456", "hello world", true, metadata);
        assertEquals("slack", msg.channelType());
        assertEquals("C123", msg.chatId());
        assertEquals("U456", msg.senderId());
        assertEquals("hello world", msg.content());
        assertTrue(msg.isMention());
        assertEquals("channel", msg.metadata().get("chat_type"));
    }
}
