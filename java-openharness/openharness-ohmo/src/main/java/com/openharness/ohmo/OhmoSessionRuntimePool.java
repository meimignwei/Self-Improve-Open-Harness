package com.openharness.ohmo;

import com.openharness.common.AgentRuntime;
import com.openharness.common.StreamEvent;
import com.openharness.common.UsageSnapshot;
import com.openharness.extensions.coordinator.CoordinatorMode;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-chat/thread session runtime pool with session resume, engine event streaming,
 * channel progress formatting, and group command context management.
 * Java equivalent of Python ohmo/gateway/runtime.py.
 */
public class OhmoSessionRuntimePool {

    private static final String[] THINKING_PHRASES = {
            "🤔 想一想…", "🧠 琢磨中…",
            "✨ 整理一下思路…", "🔎 看看这个…",
            "🪄 捋一捋线索…"
    };
    private static final String[] THINKING_PHRASES_EN = {
            "🤔 Thinking…", "🧠 Working through it…",
            "✨ Pulling the pieces together…", "🔎 Looking into it…",
            "🪄 Following the thread…"
    };

    static final String GROUP_TOOL_NAME = "ohmo_create_feishu_group";
    private static final String GROUP_AGENT_PROMPT_PREFIX = "The user invoked `/group` from a Feishu private chat.";
    private static final String GROUP_AGENT_PROMPT_REQUEST_MARKER = "User /group request:";
    private static final Object NO_GROUP_REQUEST = new Object();

    private final String cwd;
    private final Path workspaceRoot;
    private String providerProfile;
    private final String model;
    private final Integer maxTurns;
    private final AgentRuntime engine;
    private final Map<String, RuntimeBundle> bundles = new ConcurrentHashMap<>();
    private final OhmoSessionBackend sessionBackend;
    private final GroupRegistry groupRegistry;

    public OhmoSessionRuntimePool(Path workspaceRoot, String providerProfile) {
        this(workspaceRoot, providerProfile, null, null, null);
    }

    public OhmoSessionRuntimePool(Path workspaceRoot, String providerProfile,
                                  String model, Integer maxTurns) {
        this(workspaceRoot, providerProfile, model, maxTurns, null);
    }

    public OhmoSessionRuntimePool(Path workspaceRoot, String providerProfile,
                                  String model, Integer maxTurns, AgentRuntime engine) {
        this.cwd = System.getProperty("user.dir");
        this.workspaceRoot = workspaceRoot;
        this.providerProfile = providerProfile;
        this.model = model;
        this.maxTurns = maxTurns;
        this.engine = engine;
        this.sessionBackend = new OhmoSessionBackend(workspaceRoot);
        this.groupRegistry = new GroupRegistry(workspaceRoot);
        RuntimeBundle gwBundle = new RuntimeBundle("_gateway_config", Path.of(cwd), providerProfile, workspaceRoot);
        if (engine != null) gwBundle.setEngine(engine);
        this.bundles.put("_gateway_config", gwBundle);
    }

    public int activeSessions() {
        return (int) bundles.keySet().stream().filter(k -> !k.startsWith("_")).count();
    }

    // ------------------------------------------------------------------
    // Bundle management
    // ------------------------------------------------------------------

    public RuntimeBundle getBundle(String sessionKey, String latestUserPrompt, Path sessionCwd) {
        Path resolvedCwd = (sessionCwd != null ? sessionCwd : Path.of(cwd)).toAbsolutePath();
        RuntimeBundle existing = bundles.get(sessionKey);
        if (existing != null) {
            if (!existing.cwd().toAbsolutePath().equals(resolvedCwd)) {
                bundles.remove(sessionKey);
            } else {
                existing.setSystemPrompt(buildRuntimeSystemPrompt(existing, latestUserPrompt));
                return existing;
            }
        }

        Map<String, Object> snapshot = sessionBackend.loadLatestForSessionKey(sessionKey);
        RuntimeBundle bundle = new RuntimeBundle(sessionKey, resolvedCwd, providerProfile, workspaceRoot);
        if (model != null) bundle.setModel(model);
        if (maxTurns != null) {
            bundle.setMaxTurns(maxTurns);
            bundle.setEnforceMaxTurns(true);
        }
        bundle.setSystemPrompt(buildRuntimeSystemPrompt(bundle, latestUserPrompt));

        if (snapshot != null) {
            if (snapshot.get("session_id") != null) {
                bundle.setSessionId((String) snapshot.get("session_id"));
            }
            @SuppressWarnings("unchecked")
            List<Object> msgs = (List<Object>) snapshot.get("messages");
            if (msgs != null) bundle.setMessages(msgs);
            @SuppressWarnings("unchecked")
            Map<String, Object> tm = (Map<String, Object>) snapshot.get("tool_metadata");
            if (tm != null) bundle.setToolMetadata(tm);
        }

        if (engine != null) bundle.setEngine(engine);

        bundles.put(sessionKey, bundle);
        return bundle;
    }

    // ------------------------------------------------------------------
    // Stream message (main entry point)
    // ------------------------------------------------------------------

    public void streamMessage(MessageBus.InboundMessage message, String sessionKey,
                              Consumer<GatewayStreamUpdate> onUpdate) {
        String userPrompt = message.content() != null ? message.content().strip() : "";
        String commandPrompt = userPrompt;
        Path sessionCwd = cwdForMessage(message);
        RuntimeBundle bundle = getBundle(sessionKey, userPrompt, sessionCwd);

        // Check for slash commands
        String commandResult = handleSlashCommand(commandPrompt, bundle, message, sessionKey, onUpdate);
        if (commandResult != null) return;

        // Stream engine message
        streamEngineMessage(bundle, message, sessionKey, userPrompt, onUpdate);
    }

    public List<GatewayStreamUpdate> streamMessage(MessageBus.InboundMessage msg, String sessionKey) {
        List<GatewayStreamUpdate> updates = new ArrayList<>();
        streamMessage(msg, sessionKey, updates::add);
        return updates;
    }

    // ------------------------------------------------------------------
    // Slash command handling
    // ------------------------------------------------------------------

    private String handleSlashCommand(String commandPrompt, RuntimeBundle bundle,
                                       MessageBus.InboundMessage message, String sessionKey,
                                       Consumer<GatewayStreamUpdate> onUpdate) {
        String lowered = commandPrompt.toLowerCase();
        if (lowered.startsWith("/provider")) {
            String args = commandPrompt.substring("/provider".length()).strip();
            String result = handleProviderCommand(args);
            onUpdate.accept(new GatewayStreamUpdate("final", result,
                    Map.of("_session_key", sessionKey, "_command", true)));
            return result;
        }
        if (lowered.startsWith("/model")) {
            String args = commandPrompt.substring("/model".length()).strip();
            String result = handleModelCommand(args);
            onUpdate.accept(new GatewayStreamUpdate("final", result,
                    Map.of("_session_key", sessionKey, "_command", true)));
            return result;
        }
        return null;
    }

    private String handleProviderCommand(String args) {
        String[] tokens = args.split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty() || "show".equals(tokens[0])) {
            return "ohmo gateway provider_profile: " + providerProfile;
        }
        if (tokens.length == 1) {
            providerProfile = tokens[0];
            GatewayConfig config = GatewayConfig.loadFromWorkspace(workspaceRoot);
            config.withProviderProfile(tokens[0]).saveToWorkspace(workspaceRoot);
            return "ohmo gateway provider_profile set to " + tokens[0] + ". Refreshing.";
        }
        return "Usage: /provider [show|PROFILE]";
    }

    private String handleModelCommand(String args) {
        if (args.isBlank() || "show".equals(args.strip())) {
            return "ohmo gateway model: " + (model != null ? model : "default");
        }
        return "ohmo gateway model set to " + args.strip() + ". Refreshing.";
    }

    // ------------------------------------------------------------------
    // Engine message streaming
    // ------------------------------------------------------------------

    private void streamEngineMessage(RuntimeBundle bundle, MessageBus.InboundMessage message,
                                      String sessionKey, String userPrompt,
                                      Consumer<GatewayStreamUpdate> onUpdate) {
        // If coordinator mode is enabled, override system prompt with coordinator prompt
        if (CoordinatorMode.isEnabled()) {
            bundle.setSystemPrompt(CoordinatorMode.getCoordinatorSystemPrompt());
        } else {
            bundle.setSystemPrompt(buildRuntimeSystemPrompt(bundle, userPrompt));
        }

        // Emit thinking progress
        onUpdate.accept(new GatewayStreamUpdate("progress",
                formatChannelProgress(message.channel(), "thinking", "Thinking...",
                        sessionKey, userPrompt, null, null, null),
                Map.of("_progress", true, "_session_key", sessionKey)));

        // If engine is available, delegate to it; otherwise emit a stub response
        if (bundle.engine() != null) {
            streamFromEngine(bundle, message, sessionKey, userPrompt, onUpdate);
        } else {
            // Stub: emit progress and final response when engine not wired
            onUpdate.accept(new GatewayStreamUpdate("progress",
                    formatChannelProgress(message.channel(), "status",
                            "ohmo engine is initializing...", sessionKey, userPrompt,
                            null, null, null),
                    Map.of("_progress", true, "_session_key", sessionKey)));
            onUpdate.accept(new GatewayStreamUpdate("final",
                    "ohmo gateway received: " + truncate(userPrompt, 160),
                    Map.of("_session_key", sessionKey)));
        }

        saveSnapshot(bundle, sessionKey, userPrompt);
    }

    private void streamFromEngine(RuntimeBundle bundle, MessageBus.InboundMessage message,
                                   String sessionKey, String userPrompt,
                                   Consumer<GatewayStreamUpdate> onUpdate) {
        List<String> replyParts = new ArrayList<>();
        Set<String> emittedMedia = new HashSet<>();

        // Wire up engine subscription
        var subscriber = new StreamEventSubscriber(event -> {
            convertStreamEvent(event, bundle, message, sessionKey, userPrompt,
                    replyParts, emittedMedia, onUpdate);
            rememberUpdateMedia(emittedMedia, event);
        });

        var queryOpts = com.openharness.common.QueryOptions.defaults()
                .withMaxTurns(bundle.maxTurns() > 0 ? bundle.maxTurns() : 10);
        if (bundle.model() != null) queryOpts = queryOpts.withModel(bundle.model());
        if (bundle.systemPrompt() != null) queryOpts = queryOpts.withSystemPrompt(bundle.systemPrompt());
        // Limit tools to coordinator tool set when coordinator mode is enabled
        if (CoordinatorMode.isEnabled()) {
            queryOpts = queryOpts.withAllowedTools(CoordinatorMode.getTools());
        }

        bundle.engine().runQuery(convertToConversationMessages(bundle.messages()), queryOpts)
                .subscribe(subscriber);

        // Wait for completion
        try {
            subscriber.awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String reply = String.join("", replyParts).strip();
        if (!reply.isEmpty()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("_session_key", sessionKey);
            List<String> finalMedia = extractFinalReplyMedia(reply, emittedMedia);
            if (!finalMedia.isEmpty()) metadata.put("_media", finalMedia);
            onUpdate.accept(new GatewayStreamUpdate("final", reply, metadata));
        }
    }

    // ------------------------------------------------------------------
    // Stream event conversion
    // ------------------------------------------------------------------

    private void convertStreamEvent(StreamEvent event, RuntimeBundle bundle,
                                     MessageBus.InboundMessage message, String sessionKey,
                                     String content, List<String> replyParts,
                                     Set<String> emittedMedia,
                                     Consumer<GatewayStreamUpdate> onUpdate) {
        switch (event) {
            case StreamEvent.AssistantTextDelta(var text) -> replyParts.add(text);

            case StreamEvent.ToolStarted(var name, var id) -> {
                onUpdate.accept(new GatewayStreamUpdate("tool_hint",
                        formatChannelProgress(message.channel(), "tool_hint",
                                "Using " + name, sessionKey, content, null, null, null),
                        Map.of("_progress", true, "_tool_hint", true, "_session_key", sessionKey)));
            }

            case StreamEvent.ToolCompleted(var name, var id, var result) -> {
                // Java ToolResult has no metadata — inspect content for file paths
                List<String> paths = extractPathsFromContent(result.content());
                if (!paths.isEmpty()) {
                    String caption = "Generated: " + String.join(", ", paths.stream().map(p -> Path.of(p).getFileName().toString()).toList());
                    onUpdate.accept(new GatewayStreamUpdate("media", caption,
                            Map.of("_session_key", sessionKey, "_media", paths, "_tool_media", true)));
                }
            }

            case StreamEvent.StatusEvent(var msg, var level) -> {
                onUpdate.accept(new GatewayStreamUpdate("progress",
                        formatChannelProgress(message.channel(), "status", msg,
                                sessionKey, content, null, null, null),
                        Map.of("_progress", true, "_session_key", sessionKey)));
            }

            case StreamEvent.CompactProgressEvent(var removed, var remaining) -> {
                onUpdate.accept(new GatewayStreamUpdate("progress",
                        formatChannelProgress(message.channel(), "compact_progress",
                                "Compacting...", sessionKey, content, null, null, null),
                        Map.of("_progress", true, "_session_key", sessionKey, "_compact", true)));
            }

            case StreamEvent.ErrorStreamEvent(var errMsg) -> {
                onUpdate.accept(new GatewayStreamUpdate("error", errMsg,
                        Map.of("_session_key", sessionKey)));
            }

            case StreamEvent.AssistantTurnComplete(var usage) -> {
                if (replyParts.isEmpty()) {
                    replyParts.add("Done.");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Session snapshot
    // ------------------------------------------------------------------

    private void saveSnapshot(RuntimeBundle bundle, String sessionKey, String userPrompt) {
        sessionBackend.saveSnapshot(
                bundle.cwd().toString(),
                bundle.model() != null ? bundle.model() : "default",
                buildRuntimeSystemPrompt(bundle, userPrompt),
                bundle.messages(),
                bundle.totalUsage(),
                bundle.sessionId(),
                sessionKey,
                bundle.toolMetadata()
        );
    }

    private String buildRuntimeSystemPrompt(RuntimeBundle bundle, String latestUserPrompt) {
        OhmoSystemPromptBuilder builder = new OhmoSystemPromptBuilder();
        return builder.build(bundle.cwd(), bundle.workspaceRoot());
    }

    // ------------------------------------------------------------------
    // CWD resolution
    // ------------------------------------------------------------------

    private Path cwdForMessage(MessageBus.InboundMessage message) {
        Map<String, Object> record = groupRegistry.loadRecord(message.channel(), message.chatId());
        if (record != null && record.get("cwd") != null) {
            String groupCwd = (String) record.get("cwd");
            Path normalized = Path.of(groupCwd).toAbsolutePath();
            if (java.nio.file.Files.exists(normalized)) return normalized;
        }
        return Path.of(cwd);
    }

    // ------------------------------------------------------------------
    // Group context management
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Object setGroupRequestContext(RuntimeBundle bundle, MessageBus.InboundMessage message, String sessionKey) {
        Map<String, Object> metadata = bundle.toolMetadata();
        Object previous = metadata.getOrDefault("ohmo_group_request", NO_GROUP_REQUEST);
        if (!Boolean.TRUE.equals(message.metadata().get("_ohmo_group_command"))) {
            metadata.remove("ohmo_group_request");
            metadata.remove("_suppress_next_user_goal");
            return NO_GROUP_REQUEST;
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("channel", message.channel());
        request.put("chat_type", String.valueOf(message.metadata().getOrDefault("chat_type", "")).strip().toLowerCase());
        request.put("sender_id", String.valueOf(message.senderId()));
        request.put("source_chat_id", message.chatId());
        request.put("source_session_key", sessionKey);
        request.put("sender_display_name", message.metadata().get("sender_display_name"));
        request.put("raw_request", message.metadata().getOrDefault("_ohmo_group_raw_request", ""));
        request.put("used", false);
        metadata.put("_suppress_next_user_goal", true);
        metadata.put("ohmo_group_request", request);
        return previous;
    }

    void restoreGroupRequestContext(RuntimeBundle bundle, Object previous) {
        Map<String, Object> metadata = bundle.toolMetadata();
        metadata.remove("ohmo_group_request");
        metadata.remove("_suppress_next_user_goal");
    }

    // ------------------------------------------------------------------
    // Static helpers
    // ------------------------------------------------------------------

    static String formatChannelProgress(String channel, String kind, String text,
                                         String sessionKey, String content,
                                         String compactPhase, String compactTrigger,
                                         Integer attempt) {
        Set<String> supported = Set.of("feishu", "telegram", "slack", "discord",
                "matrix", "whatsapp", "email", "dingtalk", "qq", "wechat");
        if (!supported.contains(channel)) return text;

        boolean prefersChinese = prefersChineseProgress(content);

        return switch (kind) {
            case "thinking" -> {
                byte[] seed = (sessionKey + "|" + content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String[] phrases = prefersChinese ? THINKING_PHRASES : THINKING_PHRASES_EN;
                int idx;
                try {
                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    byte[] hash = sha256.digest(seed);
                    idx = (hash[0] & 0xFF) % phrases.length;
                } catch (NoSuchAlgorithmException e) {
                    idx = Math.abs(sessionKey.hashCode()) % phrases.length;
                }
                yield phrases[idx];
            }
            case "tool_hint" -> prefersChinese && text.startsWith("Using ")
                    ? "🛠️ " + text.replace("Using ", "正在使用 ")
                    : (!text.startsWith("🛠️ ") ? "🛠️ " + text : text);
            case "image_fallback" -> prefersChinese
                    ? "🖼️ 当前模型不支持图片输入，我先改用附件路径和摘要继续。"
                    : "🖼️ The active model does not support image input. I'll retry with attachment paths and summaries.";
            case "status" -> text.startsWith("🤔") || text.startsWith("🧠")
                    || text.startsWith("✨") || text.startsWith("🔎")
                    || text.startsWith("🪄") || text.startsWith("🛠️")
                    || text.startsWith("🧧") ? text : "🧧 " + text;
            default -> text;
        };
    }

    private static boolean prefersChineseProgress(String content) {
        int cjkCount = 0, latinCount = 0;
        for (int i = 0; i < content.length(); i++) {
            int cp = content.codePointAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0xF900 && cp <= 0xFAFF)) cjkCount++;
            else if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) latinCount++;
        }
        if (cjkCount == 0) return false;
        if (latinCount == 0) return true;
        return cjkCount >= latinCount;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractPathsFromContent(String content) {
        if (content == null) return List.of();
        // Extract file paths from tool result content using regex
        List<String> paths = new ArrayList<>();
        var pattern = java.util.regex.Pattern.compile(
                "(?<path>(?:[A-Za-z]:[\\\\/]|/)[^\\r\\n`\"'<>|?*\\x00]+?\\.(?:png|jpe?g|webp|gif|bmp))",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        var matcher = pattern.matcher(content);
        while (matcher.find()) {
            paths.add(matcher.group("path"));
        }
        return paths;
    }

    private static List<String> extractFinalReplyMedia(String reply, Set<String> emittedMedia) {
        List<String> media = new ArrayList<>();
        var pattern = java.util.regex.Pattern.compile(
                "(?<path>(?:[A-Za-z]:[\\\\/]|/)[^\\r\\n`\"'<>|?*\\x00]+?\\.(?:png|jpe?g|webp|gif|bmp))",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        var matcher = pattern.matcher(reply);
        while (matcher.find()) {
            String raw = matcher.group("path").replaceAll("[\\s\"'.,;:，。；：、)]}+$", "");
            Path p = Path.of(raw).toAbsolutePath();
            if (java.nio.file.Files.exists(p) && !emittedMedia.contains(p.toString())) {
                media.add(p.toString());
            }
        }
        return media;
    }

    private static void rememberUpdateMedia(Set<String> seen, StreamEvent event) {
        // No-op for simple events; ToolCompleted handles its own media tracking
    }

    private static String truncate(String s, int limit) {
        String normalized = s.replaceAll("\\s+", " ");
        if (normalized.length() <= limit) return normalized;
        return normalized.substring(0, limit - 3) + "...";
    }

    // ------------------------------------------------------------------
    // Nested types
    // ------------------------------------------------------------------

    public record GatewayStreamUpdate(String kind, String text, Map<String, Object> metadata) {
        public GatewayStreamUpdate {
            if (metadata == null) metadata = Map.of();
        }
    }

    /**
     * Simple subscriber that collects StreamEvents via a callback.
     */
    private static class StreamEventSubscriber implements java.util.concurrent.Flow.Subscriber<StreamEvent> {
        private final Consumer<StreamEvent> callback;
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        private java.util.concurrent.Flow.Subscription subscription;

        StreamEventSubscriber(Consumer<StreamEvent> callback) { this.callback = callback; }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(StreamEvent event) { callback.accept(event); }

        @Override
        public void onError(Throwable t) {
            callback.accept(new StreamEvent.ErrorStreamEvent(t.getMessage()));
            latch.countDown();
        }

        @Override
        public void onComplete() { latch.countDown(); }

        void awaitCompletion() throws InterruptedException { latch.await(); }
    }

    @SuppressWarnings("unchecked")
    private static List<com.openharness.common.ConversationMessage> convertToConversationMessages(List<Object> messages) {
        List<com.openharness.common.ConversationMessage> result = new ArrayList<>();
        for (Object msg : messages) {
            if (msg instanceof com.openharness.common.ConversationMessage cm) {
                result.add(cm);
            } else if (msg instanceof Map<?, ?> m) {
                Object roleObj = m.get("role");
                String roleStr = roleObj != null ? String.valueOf(roleObj) : "user";
                com.openharness.common.Role role = "assistant".equals(roleStr)
                        ? com.openharness.common.Role.ASSISTANT
                        : com.openharness.common.Role.USER;
                Object content = m.get("content");
                if (content instanceof String s) {
                    result.add(new com.openharness.common.ConversationMessage(
                            role, List.of(new com.openharness.common.ContentBlock.TextBlock(s))));
                } else if (content instanceof List<?> blocks) {
                    List<com.openharness.common.ContentBlock> cbs = new ArrayList<>();
                    for (Object block : blocks) {
                        if (block instanceof com.openharness.common.ContentBlock cb) {
                            cbs.add(cb);
                        } else if (block instanceof Map<?, ?> bm) {
                            Object typeObj = bm.get("type");
                            String type = typeObj != null ? String.valueOf(typeObj) : "text";
                            if ("text".equals(type)) {
                                Object textObj = bm.get("text");
                                cbs.add(new com.openharness.common.ContentBlock.TextBlock(
                                        textObj != null ? String.valueOf(textObj) : ""));
                            }
                        }
                    }
                    result.add(new com.openharness.common.ConversationMessage(role, cbs));
                }
            }
        }
        return result;
    }
}
