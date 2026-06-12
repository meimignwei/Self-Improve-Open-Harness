package com.openharness.extensions.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Slack channel integration using Web API and Socket Mode.
 * Java equivalent of Python channels/slack.py.
 */
public class SlackChannel extends BaseChannel {

    private String botToken;
    private String appToken;
    private Thread receiveThread;
    private String socketModeUrl;

    public SlackChannel(Map<String, Object> config) {
        super(config);
        this.botToken = (String) config.getOrDefault("bot_token", System.getenv("SLACK_BOT_TOKEN"));
        this.appToken = (String) config.getOrDefault("app_token", System.getenv("SLACK_APP_TOKEN"));
    }

    @Override
    public String getChannelType() { return "slack"; }

    @Override
    public void start() {
        if (botToken == null) {
            System.err.println("Slack: missing bot_token");
            return;
        }

        running = true;

        if (appToken != null) {
            connectSocketMode();
        } else {
            startPolling();
        }
    }

    private void connectSocketMode() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://slack.com/api/apps.connections.open"))
                    .header("Authorization", "Bearer " + appToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = MAPPER.readTree(resp.body());
            if (json.path("ok").asBoolean()) {
                socketModeUrl = json.path("url").asText();
                startSocketModeReceive();
            } else {
                System.err.println("Slack socket mode failed: " + json.path("error").asText());
                startPolling();
            }
        } catch (Exception e) {
            System.err.println("Slack socket mode error: " + e.getMessage());
            startPolling();
        }
    }

    private void startSocketModeReceive() {
        receiveThread = Thread.startVirtualThread(() -> {
            while (running) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(socketModeUrl))
                            .header("Authorization", "Bearer " + appToken)
                            .timeout(Duration.ofSeconds(30))
                            .GET().build();

                    HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    try { Thread.sleep(Duration.ofSeconds(5)); }
                    catch (InterruptedException ie) { break; }
                }
            }
        });
    }

    private void startPolling() {
        receiveThread = Thread.startVirtualThread(() -> {
            while (running) {
                try {
                    pollConversations();
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void pollConversations() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://slack.com/api/conversations.list?limit=10"))
                    .header("Authorization", "Bearer " + botToken)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = MAPPER.readTree(resp.body());

            if (json.path("ok").asBoolean()) {
                for (JsonNode channel : json.path("channels")) {
                    String channelId = channel.path("id").asText();
                    fetchRecentMessages(channelId);
                }
            }
        } catch (Exception ignored) {}
    }

    private void fetchRecentMessages(String channelId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://slack.com/api/conversations.history?channel="
                            + channelId + "&limit=3"))
                    .header("Authorization", "Bearer " + botToken)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = MAPPER.readTree(resp.body());

            if (json.path("ok").asBoolean()) {
                for (JsonNode msg : json.path("messages")) {
                    String userId = msg.path("user").asText();
                    if (!isAllowed(userId)) continue;

                    String text = msg.path("text").asText();
                    if (text != null && !text.isEmpty() && !msg.path("bot_id").asText().isEmpty()) {
                        boolean isMention = text.contains("<@" + botUserId + ">");
                        inbound.put(new ChannelMessage("slack", channelId, userId,
                                text, isMention, Map.of("chat_type", "channel")));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String botUserId;

    @Override
    public void send(ChannelMessage msg) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("channel", msg.chatId());
            body.put("text", msg.content());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://slack.com/api/chat.postMessage"))
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = MAPPER.readTree(resp.body());
            if (!json.path("ok").asBoolean()) {
                System.err.println("Slack send failed: " + json.path("error").asText());
            }
        } catch (Exception e) {
            System.err.println("Slack send error: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        running = false;
        if (receiveThread != null) receiveThread.interrupt();
    }
}
