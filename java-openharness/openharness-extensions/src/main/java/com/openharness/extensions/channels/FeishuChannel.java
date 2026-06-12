package com.openharness.extensions.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Feishu (Lark) channel integration using HTTP API.
 * Java equivalent of Python channels/feishu.py.
 *
 * Uses Feishu Open API:
 * - Tenant access token via app_id + app_secret
 * - Send/receive messages via im/v1
 * - WebSocket events via lark open platform
 */
public class FeishuChannel extends BaseChannel {

    private String tenantAccessToken;
    private long tokenExpiresAt;
    private Thread pollingThread;
    private String appId;
    private String appSecret;

    public FeishuChannel(Map<String, Object> config) {
        super(config);
        this.appId = (String) config.getOrDefault("app_id", System.getenv("FEISHU_APP_ID"));
        this.appSecret = (String) config.getOrDefault("app_secret", System.getenv("FEISHU_APP_SECRET"));
    }

    @Override
    public String getChannelType() { return "feishu"; }

    @Override
    public void start() {
        if (appId == null || appSecret == null) {
            System.err.println("Feishu: missing app_id or app_secret");
            return;
        }

        running = true;
        refreshToken();

        pollingThread = Thread.startVirtualThread(() -> {
            while (running) {
                try {
                    pollEvents();
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @Override
    public void stop() {
        running = false;
        if (pollingThread != null) pollingThread.interrupt();
    }

    @Override
    public void send(ChannelMessage msg) {
        try {
            ensureToken();
            ObjectNode body = MAPPER.createObjectNode();
            body.put("receive_id", msg.chatId());
            body.put("msg_type", "text");
            ObjectNode content = MAPPER.createObjectNode();
            content.put("text", msg.content());
            body.put("content", MAPPER.writeValueAsString(content));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id"))
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("Feishu send failed: " + resp.statusCode() + " " + resp.body());
            }
        } catch (Exception e) {
            System.err.println("Feishu send error: " + e.getMessage());
        }
    }

    private void pollEvents() {
        try {
            ensureToken();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/im/v1/messages?page_size=10"))
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(resp.body());
                JsonNode items = json.path("data").path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String senderId = item.path("sender").path("id").asText();
                        if (!isAllowed(senderId)) continue;

                        String chatId = item.path("chat_id").asText();
                        String msgType = item.path("msg_type").asText();
                        String content = extractTextContent(item.path("body").path("content"));

                        if (!content.isEmpty()) {
                            boolean isMention = content.contains("@");
                            inbound.put(new ChannelMessage("feishu", chatId, senderId,
                                    content, isMention, Map.of("chat_type", "group")));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String extractTextContent(JsonNode contentNode) {
        // Feishu message content is a JSON string
        String contentStr = contentNode.asText();
        try {
            JsonNode parsed = MAPPER.readTree(contentStr);
            return parsed.path("text").asText();
        } catch (Exception e) {
            return contentStr;
        }
    }

    private void refreshToken() {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("app_id", appId);
            body.put("app_secret", appSecret);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = MAPPER.readTree(resp.body());
            tenantAccessToken = json.path("tenant_access_token").asText();
            int expire = json.path("expire").asInt(7200);
            tokenExpiresAt = System.currentTimeMillis() + (expire * 1000L);
        } catch (Exception e) {
            throw new RuntimeException("Feishu auth failed", e);
        }
    }

    private void ensureToken() {
        if (tenantAccessToken == null || System.currentTimeMillis() >= tokenExpiresAt - 60000) {
            refreshToken();
        }
    }
}
