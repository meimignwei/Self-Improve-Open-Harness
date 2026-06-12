package com.openharness.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.ApiStreamEvent;
import com.openharness.common.ConversationMessage;
import com.openharness.common.ContentBlock;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.StreamingSpliterator;
import com.openharness.common.UsageSnapshot;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Anthropic Messages API client with SSE streaming.
 * Java equivalent of Python's client.py — Anthropic SSE streaming logic.
 */
public class AnthropicMessagesClient implements StreamingApiClient {

    private static final Logger LOG = Logger.getLogger(AnthropicMessagesClient.class.getName());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String anthropicVersion;

    public AnthropicMessagesClient(String apiKey, String baseUrl, String anthropicVersion) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl.replaceAll("/+$", "")
                : "https://api.anthropic.com";
        this.anthropicVersion = anthropicVersion != null ? anthropicVersion : "2023-06-01";
    }

    @Override
    public Stream<ApiStreamEvent> streamMessages(
            String model, String systemPrompt,
            List<ConversationMessage> messages,
            List<ToolDefinition> tools, StreamOptions options) {

        return ApiRetryPolicy.execute(() -> doStreamMessages(model, systemPrompt, messages, tools, options));
    }

    private Stream<ApiStreamEvent> doStreamMessages(
            String model, String systemPrompt,
            List<ConversationMessage> messages,
            List<ToolDefinition> tools, StreamOptions options) {

        BlockingQueue<ApiStreamEvent> queue = new LinkedBlockingQueue<>();
        var mapper = OpenHarnessObjectMapper.get();
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", options.maxTokens());
        requestBody.put("stream", true);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestBody.put("system", systemPrompt);
        }

        // Convert messages
        var messagesArray = mapper.createArrayNode();
        for (ConversationMessage msg : messages) {
            ObjectNode msgNode = mapper.createObjectNode();
            msgNode.put("role", msg.role().name().toLowerCase());
            var contentArray = mapper.createArrayNode();
            for (ContentBlock block : msg.content()) {
                contentArray.add(convertContentBlock(block, mapper));
            }
            msgNode.set("content", contentArray);
            messagesArray.add(msgNode);
        }
        requestBody.set("messages", messagesArray);

        // Convert tools
        if (tools != null && !tools.isEmpty() && options.enableTools()) {
            var toolsArray = mapper.createArrayNode();
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = mapper.createObjectNode();
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                toolNode.set("input_schema", mapper.valueToTree(tool.inputSchema() != null
                        ? tool.inputSchema()
                        : java.util.Map.of("type", mapper.getNodeFactory().textNode("object"))));
                toolsArray.add(toolNode);
            }
            requestBody.set("tools", toolsArray);
        }

        try {
            String json = mapper.writeValueAsString(requestBody);
            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", anthropicVersion)
                    .post(RequestBody.create(json, JSON))
                    .build();

            EventSource.Factory factory = EventSources.createFactory(httpClient);
            factory.newEventSource(request, new AnthropicEventSourceListener(queue));
        } catch (Exception e) {
            queue.add(new ApiStreamEvent.ErrorEvent("connection_error", e.getMessage()));
            queue.add(null); // sentinel
        }

        return StreamSupport.stream(new StreamingSpliterator<>(queue), false);
    }

    private JsonNode convertContentBlock(ContentBlock block, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return switch (block) {
            case ContentBlock.TextBlock tb -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "text");
                node.put("text", tb.text());
                yield node;
            }
            case ContentBlock.ImageBlock ib -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "image");
                var source = mapper.createObjectNode();
                source.put("type", "base64");
                source.put("media_type", ib.mediaType());
                source.put("data", ib.base64Data());
                node.set("source", source);
                yield node;
            }
            case ContentBlock.ToolUseBlock tub -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "tool_use");
                node.put("id", tub.id());
                node.put("name", tub.name());
                node.set("input", tub.input());
                yield node;
            }
            case ContentBlock.ToolResultBlock trb -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "tool_result");
                node.put("tool_use_id", trb.toolUseId());
                node.put("content", trb.content());
                if (trb.isError()) {
                    node.put("is_error", true);
                }
                yield node;
            }
        };
    }

    @Override
    public ProviderInfo getProviderInfo() {
        return new ProviderInfo("anthropic", "api_key", false,
                "voice mode shell exists, but live voice auth/streaming is not configured in this build");
    }

    /**
     * SSE event listener that parses Anthropic's streaming events into ApiStreamEvent objects.
     */
    private static class AnthropicEventSourceListener extends EventSourceListener {
        private final BlockingQueue<ApiStreamEvent> queue;
        private final StringBuilder toolInputBuffer = new StringBuilder();
        private String currentToolId;
        private String currentToolName;
        private int inputTokens;
        private int outputTokens;

        AnthropicEventSourceListener(BlockingQueue<ApiStreamEvent> queue) {
            this.queue = queue;
        }

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            try {
                if (data == null || data.isBlank()) return;

                var mapper = OpenHarnessObjectMapper.get();
                JsonNode event = mapper.readTree(data);

                // Track usage
                if (event.has("usage")) {
                    JsonNode usage = event.get("usage");
                    if (usage.has("input_tokens")) inputTokens = usage.get("input_tokens").asInt();
                    if (usage.has("output_tokens")) outputTokens = usage.get("output_tokens").asInt();
                }

                String eventType = event.has("type") ? event.get("type").asText() : "";

                switch (eventType) {
                    case "content_block_start" -> {
                        if (!event.has("content_block")) return;
                        JsonNode block = event.get("content_block");
                        String blockType = block.get("type").asText();
                        if ("tool_use".equals(blockType)) {
                            currentToolId = block.get("id").asText();
                            currentToolName = block.get("name").asText();
                            toolInputBuffer.setLength(0);
                            queue.add(new ApiStreamEvent.ToolUseStart(currentToolId, currentToolName));
                        }
                    }
                    case "content_block_delta" -> {
                        if (!event.has("delta")) return;
                        JsonNode delta = event.get("delta");
                        String deltaType = delta.get("type").asText();
                        if ("text_delta".equals(deltaType)) {
                            String text = delta.get("text").asText();
                            queue.add(new ApiStreamEvent.ContentDelta(text, 0));
                        } else if ("input_json_delta".equals(deltaType)) {
                            String partial = delta.get("partial_json").asText();
                            toolInputBuffer.append(partial);
                            queue.add(new ApiStreamEvent.ToolUseInputDelta(currentToolId, partial));
                        }
                    }
                    case "content_block_stop" -> {
                        if (currentToolId != null) {
                            try {
                                JsonNode input = mapper.readTree(toolInputBuffer.toString());
                                queue.add(new ApiStreamEvent.ToolUseComplete(
                                        currentToolId, currentToolName, input));
                            } catch (Exception e) {
                                queue.add(new ApiStreamEvent.ToolUseComplete(
                                        currentToolId, currentToolName,
                                        mapper.createObjectNode()));
                            }
                            currentToolId = null;
                            currentToolName = null;
                            toolInputBuffer.setLength(0);
                        }
                    }
                    case "message_stop" -> {
                        queue.add(new ApiStreamEvent.TurnComplete(
                                new UsageSnapshot(inputTokens, outputTokens),
                                event.has("stop_reason") ? event.get("stop_reason").asText() : "end_turn"));
                    }
                    case "error" -> {
                        String message = event.has("error")
                                ? event.get("error").get("message").asText()
                                : "Unknown error";
                        queue.add(new ApiStreamEvent.ErrorEvent("api_error", message));
                    }
                }
            } catch (IOException e) {
                LOG.warning("Failed to parse SSE event: " + e.getMessage());
            }
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            String msg = "SSE connection failed";
            if (response != null) {
                int code = response.code();
                msg = "HTTP " + code + ": " + response.message();
                if (code == 401) {
                    queue.add(new ApiStreamEvent.ErrorEvent("auth_error", msg));
                } else if (code == 429) {
                    queue.add(new ApiStreamEvent.ErrorEvent("rate_limit_error", msg));
                } else {
                    queue.add(new ApiStreamEvent.ErrorEvent("server_error", msg));
                }
            } else {
                queue.add(new ApiStreamEvent.ErrorEvent("connection_error",
                        t != null ? t.getMessage() : msg));
            }
            queue.add(null); // sentinel: stream complete
        }

        @Override
        public void onClosed(EventSource eventSource) {
            queue.add(null); // sentinel: stream complete
        }
    }
}
