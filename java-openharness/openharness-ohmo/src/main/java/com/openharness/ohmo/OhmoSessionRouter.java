package com.openharness.ohmo;

import java.util.Map;
import java.util.Set;

/**
 * Computes session keys: private chats persist, group chats isolate by sender.
 * Java equivalent of Python ohmo/gateway/router.py.
 */
public class OhmoSessionRouter {

    private static final Set<String> SHARED_TYPES = Set.of(
            "group", "chat", "supergroup", "channel", "room");

    public String sessionKeyFor(MessageBus.InboundMessage msg) {
        if (msg.sessionKeyOverride() != null) return msg.sessionKeyOverride();

        Map<String, Object> meta = msg.metadata();
        String sender = meta.getOrDefault("sender_id", "anonymous").toString();
        String chatType = meta.getOrDefault("chat_type", "").toString().toLowerCase();
        boolean isShared = SHARED_TYPES.contains(chatType);

        Object threadId = meta.get("thread_id");
        if (threadId == null) threadId = meta.get("thread_ts");
        if (threadId == null) threadId = meta.get("message_thread_id");

        if (threadId != null) {
            return isShared
                    ? "%s:%s:%s:%s".formatted(msg.channel(), msg.chatId(), threadId, sender)
                    : "%s:%s:%s".formatted(msg.channel(), msg.chatId(), threadId);
        }
        return isShared
                ? "%s:%s:%s".formatted(msg.channel(), msg.chatId(), sender)
                : "%s:%s".formatted(msg.channel(), msg.chatId());
    }
}
