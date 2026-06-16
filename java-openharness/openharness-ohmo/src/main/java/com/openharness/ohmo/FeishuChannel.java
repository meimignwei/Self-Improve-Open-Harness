package com.openharness.ohmo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real Feishu (Lark) bot channel using Open API.
 *
 * <h3>Configuration</h3>
 * In gateway.json channel_configs.feishu:
 * <pre>
 * {
 *   "app_id": "cli_xxx",
 *   "app_secret": "xxx",
 *   "verification_token": "xxx",
 *   "encrypt_key": "xxx" (optional, for encrypted events),
 *   "webhook_port": 18080 (default),
 *   "webhook_path": "/feishu/webhook" (default)
 * }
 * </pre>
 *
 * <h3>How it works</h3>
 * 1. Gets tenant_access_token via app_id + app_secret
 * 2. Starts an embedded HTTP server to receive Feishu event callbacks
 * 3. Parses im.message.receive_v1 events and publishes to MessageBus
 * 4. Sends replies via Feishu message API (text and card)
 */
public class FeishuChannel implements ChannelManager.ChannelConnection {

    private static final Logger logger = LoggerFactory.getLogger(FeishuChannel.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String FEISHU_HOST = "https://open.feishu.cn";

    private final Map<String, Object> config;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private String tenantAccessToken;
    private long tokenExpiresAt;
    private MessageBus messageBus;
    private com.sun.net.httpserver.HttpServer webhookServer;

    public FeishuChannel(Map<String, Object> config) {
        this.config = config != null ? new ConcurrentHashMap<>(config) : new ConcurrentHashMap<>();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "feishu-channel");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------
    // ChannelConnection protocol
    // ------------------------------------------------------------------

    @Override
    public void sendMessage(MessageBus.OutboundMessage msg) {
        sendText(msg.chatId(), msg.text(), msg.metadata());
    }

    @Override
    public void connect(MessageBus bus) {
        if (!running.compareAndSet(false, true)) {
            logger.warn("Feishu channel already connected");
            return;
        }
        this.messageBus = bus;

        try {
            refreshToken();
            startWebhookServer();
            logger.info("Feishu channel connected (app_id={}, webhook_port={})",
                    config.get("app_id"), getWebhookPort());
        } catch (Exception e) {
            running.set(false);
            throw new RuntimeException("Failed to connect Feishu channel: " + e.getMessage(), e);
        }

        // Token refresh every 90 minutes (tokens live ~2 hours)
        scheduler.scheduleAtFixedRate(() -> {
            try { refreshToken(); } catch (Exception e) {
                logger.error("Feishu token refresh failed", e);
            }
        }, 90, 90, TimeUnit.MINUTES);
    }

    @Override
    public void disconnect() {
        running.set(false);
        scheduler.shutdownNow();
        if (webhookServer != null) {
            webhookServer.stop(3);
            webhookServer = null;
        }
        logger.info("Feishu channel disconnected");
    }

    // ------------------------------------------------------------------
    // Token management
    // ------------------------------------------------------------------

    private void refreshToken() throws IOException {
        String appId = (String) config.get("app_id");
        String appSecret = (String) config.get("app_secret");
        if (appId == null || appSecret == null) {
            throw new IllegalStateException("Feishu app_id and app_secret are required in channel_configs.feishu");
        }

        ObjectNode body = OpenHarnessObjectMapper.get().createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);

        Request request = new Request.Builder()
                .url(FEISHU_HOST + "/open-apis/auth/v3/tenant_access_token/internal")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Feishu auth failed: " + response.code() + " " + response.message());
            }
            JsonNode json = OpenHarnessObjectMapper.get().readTree(response.body().string());
            int code = json.get("code").asInt();
            if (code != 0) {
                throw new IOException("Feishu auth error: " + json.get("msg").asText());
            }
            this.tenantAccessToken = json.get("tenant_access_token").asText();
            this.tokenExpiresAt = System.currentTimeMillis() + json.get("expire").asLong() * 1000;
            logger.debug("Feishu token refreshed, expires at {}", Instant.ofEpochMilli(tokenExpiresAt));
        }
    }

    private String getToken() {
        if (tenantAccessToken == null || System.currentTimeMillis() >= tokenExpiresAt - 60_000) {
            try { refreshToken(); } catch (IOException e) {
                throw new RuntimeException("Feishu token refresh failed", e);
            }
        }
        return tenantAccessToken;
    }

    // ------------------------------------------------------------------
    // Webhook server
    // ------------------------------------------------------------------

    private void startWebhookServer() throws IOException {
        int port = getWebhookPort();
        String path = getWebhookPath();

        webhookServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        webhookServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        webhookServer.createContext(path, exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            byte[] body = exchange.getRequestBody().readAllBytes();
            String bodyStr = new String(body, StandardCharsets.UTF_8);

            try {
                JsonNode json = OpenHarnessObjectMapper.get().readTree(bodyStr);

                // Handle URL verification challenge
                if (json.has("challenge")) {
                    String challenge = json.get("challenge").asText();
                    ObjectNode resp = OpenHarnessObjectMapper.get().createObjectNode();
                    resp.put("challenge", challenge);
                    byte[] respBytes = resp.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, respBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(respBytes);
                    }
                    logger.info("Feishu webhook URL verification successful");
                    return;
                }

                // Verify token
                String token = json.has("token") ? json.get("token").asText() : "";
                String expectedToken = (String) config.getOrDefault("verification_token", "");
                if (!expectedToken.isEmpty() && !expectedToken.equals(token)) {
                    logger.warn("Feishu webhook token mismatch");
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                    return;
                }

                // Handle encrypted events
                String encryptKey = (String) config.get("encrypt_key");
                if (encryptKey != null && !encryptKey.isEmpty() && json.has("encrypt")) {
                    String decrypted = decryptFeishuEvent(json.get("encrypt").asText(), encryptKey);
                    if (decrypted != null) {
                        json = OpenHarnessObjectMapper.get().readTree(decrypted);
                    }
                }

                // Process events
                if (json.has("event")) {
                    processFeishuEvent(json);
                }

                // Ack
                ObjectNode ack = OpenHarnessObjectMapper.get().createObjectNode();
                ack.put("code", 0);
                byte[] ackBytes = ack.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ackBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(ackBytes);
                }
            } catch (Exception e) {
                logger.error("Feishu webhook error", e);
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });

        webhookServer.start();
        logger.info("Feishu webhook server listening on http://0.0.0.0:{}{}", port, path);
    }

    // ------------------------------------------------------------------
    // Event processing
    // ------------------------------------------------------------------

    private void processFeishuEvent(JsonNode json) {
        JsonNode event = json.get("event");
        if (event == null) return;

        String eventType = json.has("type") ? json.get("type").asText() : "";
        if (!"im.message.receive_v1".equals(eventType)) return;

        JsonNode message = event.get("message");
        if (message == null) return;

        String messageType = message.has("message_type") ? message.get("message_type").asText() : "";
        if (!"text".equals(messageType)) return; // only handle text for now

        String chatId = message.has("chat_id") ? message.get("chat_id").asText() : "";
        String messageId = message.has("message_id") ? message.get("message_id").asText() : "";
        String content = extractTextContent(message);
        String chatType = message.has("chat_type") ? message.get("chat_type").asText() : "private";

        // Extract sender
        JsonNode sender = event.get("sender");
        String senderId = "";
        String senderName = "";
        if (sender != null) {
            if (sender.has("sender_id")) {
                JsonNode sid = sender.get("sender_id");
                senderId = sid.has("open_id") ? sid.get("open_id").asText() : sid.asText();
            }
            senderName = sender.has("sender_name") ? sender.get("sender_name").asText() : "";
        }

        // Check if bot is mentioned (for group policy enforcement)
        boolean mentionsBot = checkMentionsBot(message);

        Map<String, Object> metadata = new ConcurrentHashMap<>();
        metadata.put("chat_type", chatType);
        metadata.put("message_id", messageId);
        metadata.put("sender_display_name", senderName);
        metadata.put("mentions_bot", mentionsBot);
        metadata.put("thread_id", message.has("thread_id") ? message.get("thread_id").asText() : "");

        MessageBus.InboundMessage inbound = new MessageBus.InboundMessage(
                "feishu", chatId, senderId, content, !"private".equals(chatType), metadata, null);

        try {
            messageBus.publishInbound(inbound);
            logger.debug("Feishu inbound: chat={} sender={} content={}",
                    chatId, senderId, OhmoGatewayBridge.contentSnippet(content));
        } catch (Exception e) {
            logger.error("Feishu failed to publish inbound message", e);
        }
    }

    // ------------------------------------------------------------------
    // Message sending
    // ------------------------------------------------------------------

    /**
     * Send a text message to a Feishu chat.
     */
    public void sendText(String chatId, String text, Map<String, Object> metadata) {
        ObjectNode content = OpenHarnessObjectMapper.get().createObjectNode();
        content.put("text", text != null ? text : "");

        ObjectNode body = OpenHarnessObjectMapper.get().createObjectNode();
        body.put("receive_id", chatId);
        body.put("msg_type", "text");
        body.put("content", content.toString());

        sendApi("/open-apis/im/v1/messages?receive_id_type=chat_id", body);
    }

    /**
     * Send an interactive card message.
     */
    public void sendCard(String chatId, String cardJson) {
        ObjectNode body = OpenHarnessObjectMapper.get().createObjectNode();
        body.put("receive_id", chatId);
        body.put("msg_type", "interactive");
        body.put("content", cardJson);

        sendApi("/open-apis/im/v1/messages?receive_id_type=chat_id", body);
    }

    /**
     * Reply to a specific message in thread.
     */
    public void replyInThread(String messageId, String text) {
        ObjectNode content = OpenHarnessObjectMapper.get().createObjectNode();
        content.put("text", text != null ? text : "");

        ObjectNode body = OpenHarnessObjectMapper.get().createObjectNode();
        body.put("msg_type", "text");
        body.put("content", content.toString());

        sendApi("/open-apis/im/v1/messages/" + messageId + "/reply", body);
    }

    private void sendApi(String path, ObjectNode body) {
        try {
            Request request = new Request.Builder()
                    .url(FEISHU_HOST + path)
                    .header("Authorization", "Bearer " + getToken())
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("Feishu send failed: {} {}", response.code(), response.message());
                    return;
                }
                JsonNode json = OpenHarnessObjectMapper.get().readTree(response.body().string());
                int code = json.get("code").asInt();
                if (code != 0) {
                    logger.warn("Feishu send error: {} {}", code, json.get("msg").asText());
                }
            }
        } catch (Exception e) {
            logger.error("Feishu send API error", e);
        }
    }

    // ------------------------------------------------------------------
    // Content parsing
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String extractTextContent(JsonNode message) {
        try {
            String contentStr = message.has("content") ? message.get("content").asText() : "";
            if (contentStr.isBlank()) return "";
            JsonNode content = OpenHarnessObjectMapper.get().readTree(contentStr);
            return content.has("text") ? content.get("text").asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private boolean checkMentionsBot(JsonNode message) {
        if (!message.has("mentions")) return false;
        JsonNode mentions = message.get("mentions");
        if (!mentions.isArray()) return false;
        String botOpenId = (String) config.get("bot_open_id");
        for (JsonNode mention : mentions) {
            if (mention.has("key") && mention.get("key").asText().equals(botOpenId)) {
                return true;
            }
            if (mention.has("id") && mention.has("id_type")) {
                String id = mention.get("id").asText();
                if (id.equals(botOpenId)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Event decryption (Feishu encrypt_key support)
    // ------------------------------------------------------------------

    private String decryptFeishuEvent(String encrypt, String key) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(key.getBytes(StandardCharsets.UTF_8));

            byte[] encrypted = java.util.Base64.getDecoder().decode(encrypt);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] iv = new byte[16];
            System.arraycopy(encrypted, 0, iv, 0, 16);
            byte[] ciphertext = new byte[encrypted.length - 16];
            System.arraycopy(encrypted, 16, ciphertext, 0, ciphertext.length);

            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(iv);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec);

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Feishu decrypt failed", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Config helpers
    // ------------------------------------------------------------------

    private int getWebhookPort() {
        Object raw = config.get("webhook_port");
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) {}
        }
        return 18080;
    }

    private String getWebhookPath() {
        Object raw = config.get("webhook_path");
        return raw != null ? raw.toString() : "/feishu/webhook";
    }
}
