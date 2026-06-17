package com.openharness.ohmo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes inbound messages, routes through session pool, publishes outbound.
 * Handles /stop, /restart, /group commands, session interruption,
 * and Feishu group policy enforcement.
 * Java equivalent of Python ohmo/gateway/bridge.py.
 */
public class OhmoGatewayBridge {

    private static final Logger logger = LoggerFactory.getLogger(OhmoGatewayBridge.class);

    private final MessageBus bus;
    private final OhmoSessionRuntimePool runtimePool;
    private final String feishuGroupPolicy;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Thread> sessionTasks = new ConcurrentHashMap<>();
    private final Map<String, String> sessionCancelReasons = new ConcurrentHashMap<>();
    private final Path workspaceRoot;

    public OhmoGatewayBridge(MessageBus bus, OhmoSessionRuntimePool runtimePool,
                             String workspace) {
        this.bus = bus;
        this.runtimePool = runtimePool;
        this.workspaceRoot = workspace != null ? Path.of(workspace) : Path.of(System.getProperty("user.home"), ".ohmo");
        String feishuConfig = GatewayConfig.loadFromWorkspace(this.workspaceRoot)
                .channelConfigs().getOrDefault("feishu", Map.of())
                .getOrDefault("group_policy", "managed_or_mention")
                .toString();
        this.feishuGroupPolicy = normalizeFeishuGroupPolicy(feishuConfig);
    }

    // ------------------------------------------------------------------
    // Main loop
    // ------------------------------------------------------------------

    public void run() {
        running.set(true);
        while (running.get()) {
            try {
                MessageBus.InboundMessage msg = bus.consumeInbound(Duration.ofSeconds(1));
                if (msg == null) continue;
                if (!shouldProcessMessage(msg)) {
                    logger.info("ohmo inbound ignored channel={} chat_id={} sender_id={} reason=feishu_group_policy policy={}",
                            msg.channel(), msg.chatId(), msg.senderId(), feishuGroupPolicy);
                    continue;
                }

                String sessionKey = new OhmoSessionRouter().sessionKeyFor(msg);
                logger.info("ohmo inbound received channel={} chat_id={} session_key={} content={}",
                        msg.channel(), msg.chatId(), sessionKey, contentSnippet(msg.content()));

                // Handle /stop
                if ("/stop".equals(msg.content().strip())) {
                    handleStop(msg, sessionKey);
                    continue;
                }

                // Handle /restart
                if ("/restart".equals(msg.content().strip())) {
                    handleRestart(msg, sessionKey);
                    continue;
                }

                // Handle /group command
                MessageBus.InboundMessage prepared = prepareGroupPromptMessage(msg, sessionKey);
                if (prepared != null) {
                    msg = prepared;
                    sessionKey = new OhmoSessionRouter().sessionKeyFor(msg);
                }

                // Interrupt previous session for this key
                interruptSession(sessionKey, "replaced by a newer user message",
                        new MessageBus.OutboundMessage(msg.chatId(),
                                "⏹️ 已停止上一条正在处理的任务，继续看你的最新消息。",
                                msg.channel(), Map.of("_progress", true, "_session_key", sessionKey)));

                // Process in a virtual thread
                MessageBus.InboundMessage finalMsg = msg;
                String finalKey = sessionKey;
                Thread[] taskHolder = new Thread[1];
                Thread task = Thread.startVirtualThread(() -> {
                    try {
                        processMessage(finalMsg, finalKey);
                    } catch (Exception e) {
                        logger.error("ohmo session error key={}", finalKey, e);
                    } finally {
                        sessionTasks.remove(finalKey, taskHolder[0]);
                        sessionCancelReasons.remove(finalKey);
                    }
                });
                taskHolder[0] = task;
                sessionTasks.put(sessionKey, task);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void shutdown() {
        running.set(false);
        for (var entry : sessionTasks.entrySet()) {
            sessionCancelReasons.put(entry.getKey(), "gateway stopping");
            entry.getValue().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Command handlers
    // ------------------------------------------------------------------

    private void handleStop(MessageBus.InboundMessage msg, String sessionKey) {
        boolean stopped = interruptSession(sessionKey, "stopped by user command", null);
        String content = stopped ? "⏹️ 已停止当前正在运行的任务。" : "当前没有正在运行的任务。";
        try {
            bus.publishOutbound(new MessageBus.OutboundMessage(
                    msg.chatId(), content, msg.channel(), Map.of("_session_key", sessionKey)));
        } catch (Exception ignored) {}
    }

    private void handleRestart(MessageBus.InboundMessage msg, String sessionKey) {
        interruptSession(sessionKey, "restarting gateway by user command", null);
        try {
            bus.publishOutbound(new MessageBus.OutboundMessage(
                    msg.chatId(), "🔄 正在重启 gateway，马上回来。\nRestarting the gateway now. I'll be back in a moment.",
                    msg.channel(), Map.of("_session_key", sessionKey)));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // Group command handling
    // ------------------------------------------------------------------

    private MessageBus.InboundMessage prepareGroupPromptMessage(
            MessageBus.InboundMessage msg, String sessionKey) {
        String args = parseGroupCommand(msg.content());
        if (args == null || !"feishu".equals(msg.channel())) return null;

        // Check if private chat
        String chatType = String.valueOf(msg.metadata().getOrDefault("chat_type", "")).strip().toLowerCase();
        boolean isPrivate = Set.of("p2p", "private", "im", "direct").contains(chatType)
                || (chatType.isEmpty() && msg.chatId().equals(msg.senderId()));
        if (!isPrivate) {
            try {
                bus.publishOutbound(new MessageBus.OutboundMessage(
                        msg.chatId(),
                        "请在和 ohmo 的私聊里使用 /group 创建新群。\nUse /group in a private chat with ohmo to create a new group.",
                        msg.channel(), Map.of("_session_key", sessionKey)));
            } catch (Exception ignored) {}
            return null;
        }

        Map<String, Object> metadata = new ConcurrentHashMap<>(msg.metadata());
        metadata.put("_ohmo_group_command", true);
        metadata.put("_ohmo_group_raw_request", args);
        String prompt = buildGroupAgentPrompt(args);
        return new MessageBus.InboundMessage(
                msg.channel(), msg.chatId(), msg.senderId(), prompt,
                msg.isMention(), metadata, msg.sessionKeyOverride());
    }

    private String buildGroupAgentPrompt(String rawRequest) {
        String request = rawRequest.isBlank() ? "(user did not provide details)" : rawRequest;
        return """
                The user invoked `/group` from a Feishu private chat.
                Your task is to create a dedicated Feishu group for this request.

                Use the `ohmo_create_feishu_group` tool exactly once if you can infer a safe group name.
                You, the model, must decide the final `name`, optional `repo`, and optional `cwd` from the user's
                natural-language request and available local context. If the cwd is not obvious, inspect the filesystem
                before calling the tool. If there is not enough information to choose safely, ask one concise clarification
                instead of calling the tool. Do not create the group via bash or direct API calls.

                User /group request:
                """ + request;
    }

    // ------------------------------------------------------------------
    // Session interruption
    // ------------------------------------------------------------------

    private boolean interruptSession(String sessionKey, String reason,
                                      MessageBus.OutboundMessage notify) {
        Thread task = sessionTasks.get(sessionKey);
        if (task == null || !task.isAlive()) return false;
        sessionCancelReasons.put(sessionKey, reason);
        task.interrupt();
        if (notify != null) {
            try { bus.publishOutbound(notify); } catch (Exception ignored) {}
        }
        try {
            task.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Message processing
    // ------------------------------------------------------------------

    private void processMessage(MessageBus.InboundMessage msg, String sessionKey) {
        Map<String, Object> inboundMeta = new ConcurrentHashMap<>();
        if (msg.metadata().containsKey("thread_id")) {
            inboundMeta.put("thread_id", msg.metadata().get("thread_id"));
        }
        String chatType = String.valueOf(msg.metadata().getOrDefault("chat_type", "")).toLowerCase();
        if ("group".equals(chatType) || inboundMeta.containsKey("thread_id")) {
            if (msg.metadata().containsKey("message_id")) {
                inboundMeta.put("message_id", msg.metadata().get("message_id"));
            }
        }

        try {
            String reply = "";
            List<String> finalMedia = List.of();
            Map<String, Object> finalMetadata = Map.of();

            List<OhmoSessionRuntimePool.GatewayStreamUpdate> updates =
                    runtimePool.streamMessage(msg, sessionKey);

            for (var update : updates) {
                if ("final".equals(update.kind())) {
                    reply = update.text();
                    @SuppressWarnings("unchecked")
                    List<String> media = (List<String>) update.metadata().getOrDefault("_media", List.of());
                    finalMedia = media;
                    finalMetadata = update.metadata();
                    continue;
                }
                if (update.text() == null || update.text().isEmpty()) continue;

                logger.info("ohmo outbound update channel={} chat_id={} session_key={} kind={} content={}",
                        msg.channel(), msg.chatId(), sessionKey, update.kind(), contentSnippet(update.text()));

                Map<String, Object> outMeta = new ConcurrentHashMap<>(inboundMeta);
                outMeta.putAll(update.metadata());
                bus.publishOutbound(new MessageBus.OutboundMessage(
                        msg.chatId(), update.text(), msg.channel(), outMeta));
            }

            if (!reply.isEmpty()) {
                logger.info("ohmo outbound final channel={} chat_id={} session_key={} content={}",
                        msg.channel(), msg.chatId(), sessionKey, contentSnippet(reply));
                Map<String, Object> outMeta = new ConcurrentHashMap<>(inboundMeta);
                outMeta.putAll(finalMetadata);
                outMeta.put("_session_key", sessionKey);
                if (!finalMedia.isEmpty()) outMeta.put("_media", finalMedia);
                bus.publishOutbound(new MessageBus.OutboundMessage(
                        msg.chatId(), reply, msg.channel(), outMeta));
            }
        } catch (Exception e) {
            logger.error("ohmo gateway failed to process inbound channel={} chat_id={} sender_id={} session_key={}",
                    msg.channel(), msg.chatId(), msg.senderId(), sessionKey, e);
            String error = formatGatewayError(e);
            try {
                bus.publishOutbound(new MessageBus.OutboundMessage(
                        msg.chatId(), error, msg.channel(), Map.of("_session_key", sessionKey)));
            } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // Feishu group policy
    // ------------------------------------------------------------------

    private boolean shouldProcessMessage(MessageBus.InboundMessage msg) {
        if (!"feishu".equals(msg.channel())) return true;

        String chatType = String.valueOf(msg.metadata().getOrDefault("chat_type", "")).strip().toLowerCase();
        if (!"group".equals(chatType)) return true;

        return switch (feishuGroupPolicy) {
            case "open" -> true;
            case "mention" -> messageMentionsBot(msg);
            case "managed" -> isManagedFeishuGroup(msg.chatId());
            case "managed_or_mention" ->
                    messageMentionsBot(msg) || isManagedFeishuGroup(msg.chatId());
            default -> messageMentionsBot(msg);
        };
    }

    private boolean isManagedFeishuGroup(String chatId) {
        try {
            GroupRegistry registry = new GroupRegistry(workspaceRoot);
            return registry.loadRecord("feishu", chatId) != null;
        } catch (Exception e) {
            logger.warn("ohmo failed to load managed group metadata chat_id={}", chatId, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Static helpers (matching Python helpers)
    // ------------------------------------------------------------------

    static String contentSnippet(String text) {
        return contentSnippet(text, 160);
    }

    static String contentSnippet(String text, int limit) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ");
        if (normalized.length() <= limit) return normalized;
        return normalized.substring(0, limit - 3) + "...";
    }

    static String formatGatewayError(Exception exc) {
        String message = exc.getMessage() != null ? exc.getMessage().strip() : exc.getClass().getSimpleName();
        String lowered = message.toLowerCase();

        if (lowered.contains("claude oauth refresh failed")) {
            return "[ohmo gateway error] Claude subscription auth refresh failed. Run `oh auth claude-login` again or switch the gateway profile.";
        }
        if (lowered.contains("claude oauth refresh token is invalid or expired")) {
            return "[ohmo gateway error] Claude subscription token is expired. Run `claude auth login`, then `oh auth claude-login`, or switch the gateway profile.";
        }
        if (lowered.contains("auth source not found") || lowered.contains("access token")) {
            return "[ohmo gateway error] Authentication is not configured for the current gateway profile. Run `oh setup` or `ohmo config`.";
        }
        if (lowered.contains("api key") || lowered.contains("auth") || lowered.contains("credential")) {
            return "[ohmo gateway error] Authentication failed for the current gateway profile. Check `oh auth status` and `ohmo config`.";
        }
        return "[ohmo gateway error] " + message;
    }

    static String normalizeFeishuGroupPolicy(String value) {
        String normalized = (value != null ? value : "").strip().toLowerCase().replace("-", "_");
        Map<String, String> aliases = Map.of(
                "all", "open", "always", "open", "always_reply", "open",
                "managed_mention", "managed_or_mention", "managed_or_at", "managed_or_mention",
                "at", "mention", "mentions", "mention"
        );
        normalized = aliases.getOrDefault(normalized, normalized);
        if (Set.of("open", "mention", "managed", "managed_or_mention").contains(normalized)) {
            return normalized;
        }
        return "managed_or_mention";
    }

    static boolean messageMentionsBot(MessageBus.InboundMessage msg) {
        Object value = msg.metadata().get("mentions_bot");
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            return Set.of("1", "true", "yes", "y").contains(s.strip().toLowerCase());
        }
        return false;
    }

    static String parseGroupCommand(String content) {
        if (content == null) return null;
        String stripped = content.strip();
        String[] parts = stripped.split("\\s+", 2);
        if (parts.length == 0 || !"/group".equals(parts[0])) return null;
        return parts.length == 1 ? "" : parts[1].strip();
    }
}