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
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * OpenAI-compatible Chat Completions API client with SSE streaming.
 * Java equivalent of Python's openai_client.py.
 * <p>
 * Supports 20+ OpenAI-compatible providers through a uniform interface.
 */
public class OpenAICompatibleClient implements StreamingApiClient {

    private static final Logger LOG = Logger.getLogger(OpenAICompatibleClient.class.getName());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final ProviderInfo providerInfo;

    public OpenAICompatibleClient(String apiKey, String baseUrl) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl.replaceAll("/+$", "")
                : "https://api.openai.com/v1";
        this.providerInfo = ProviderInfo.apiKey("openai-compatible");
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

        // Build messages array with optional system prompt
        var messagesArray = mapper.createArrayNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysNode = mapper.createObjectNode();
            sysNode.put("role", "system");
            sysNode.put("content", systemPrompt);
            messagesArray.add(sysNode);
        }

        for (ConversationMessage msg : messages) {
            ObjectNode msgNode = mapper.createObjectNode();
            msgNode.put("role", msg.role().name().toLowerCase());
            // OpenAI uses a simpler content model — concatenate text blocks
            StringBuilder content = new StringBuilder();
            var toolCalls = mapper.createArrayNode();
            for (ContentBlock block : msg.content()) {
                switch (block) {
                    case ContentBlock.TextBlock tb -> content.append(tb.text());
                    case ContentBlock.ToolUseBlock tub -> {
                        ObjectNode tc = mapper.createObjectNode();
                        tc.put("id", tub.id());
                        tc.put("type", "function");
                        var func = mapper.createObjectNode();
                        func.put("name", tub.name());
                        func.put("arguments", tub.input().toString());
                        tc.set("function", func);
                        toolCalls.add(tc);
                    }
                    case ContentBlock.ToolResultBlock trb -> {
                        msgNode.put("role", "tool");
                        msgNode.put("tool_call_id", trb.toolUseId());
                        msgNode.put("content", trb.content());
                    }
                    case ContentBlock.ImageBlock ib -> {
                        var imageContent = mapper.createObjectNode();
                        imageContent.put("type", "image_url");
                        var imageUrl = mapper.createObjectNode();
                        imageUrl.put("url", "data:" + ib.mediaType() + ";base64," + ib.base64Data());
                        imageContent.set("image_url", imageUrl);
                        messagesArray.add(imageContent);
                        // Use multi-content format
                        var arr = mapper.createArrayNode();
                        arr.add(imageContent);
                        msgNode.set("content", arr);
                    }
                }
            }

            if ("tool".equals(msgNode.get("role").asText())) {
                // already set above
            } else if (toolCalls.size() > 0) {
                msgNode.put("content", content.isEmpty() ? null : content.toString());
                msgNode.set("tool_calls", toolCalls);
            } else {
                msgNode.put("content", content.toString());
            }
            messagesArray.add(msgNode);
        }
        requestBody.set("messages", messagesArray);

        // Convert tools to OpenAI function format
        if (tools != null && !tools.isEmpty() && options.enableTools()) {
            var toolsArray = mapper.createArrayNode();
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = mapper.createObjectNode();
                toolNode.put("type", "function");
                var func = mapper.createObjectNode();
                func.put("name", tool.name());
                func.put("description", tool.description());
                func.set("parameters", mapper.valueToTree(tool.inputSchema() != null
                        ? tool.inputSchema()
                        : java.util.Map.of("type", mapper.getNodeFactory().textNode("object"))));
                toolNode.set("function", func);
                toolsArray.add(toolNode);
            }
            requestBody.set("tools", toolsArray);
        }

        try {
            String json = mapper.writeValueAsString(requestBody);
            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();

            EventSource.Factory factory = EventSources.createFactory(httpClient);
            factory.newEventSource(request, new OpenAiEventSourceListener(queue));
        } catch (Exception e) {
            queue.add(new ApiStreamEvent.ErrorEvent("connection_error", e.getMessage()));
            queue.add(null);
        }

        return StreamSupport.stream(new StreamingSpliterator<>(queue), false);
    }

    @Override
    public ProviderInfo getProviderInfo() {
        return providerInfo;
    }

    /**
     * SSE event listener for OpenAI-compatible streaming.
     */
    private static class OpenAiEventSourceListener extends EventSourceListener {
        private final BlockingQueue<ApiStreamEvent> queue;
        private final StringBuilder toolInputBuffer = new StringBuilder();
        private String currentToolId;
        private String currentToolName;
        private int inputTokens;
        private int outputTokens;

        OpenAiEventSourceListener(BlockingQueue<ApiStreamEvent> queue) {
            this.queue = queue;
        }

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            try {
                if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
                    queue.add(new ApiStreamEvent.TurnComplete(
                            new UsageSnapshot(inputTokens, outputTokens), "stop"));
                    return;
                }

                var mapper = OpenHarnessObjectMapper.get();
                JsonNode event = mapper.readTree(data);
                var choices = event.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) return;

                JsonNode choice = choices.get(0);
                if (choice == null) return;

                // Usage tracking
                if (event.has("usage")) {
                    JsonNode usage = event.get("usage");
                    if (usage.has("prompt_tokens")) inputTokens = usage.get("prompt_tokens").asInt();
                    if (usage.has("completion_tokens")) outputTokens = usage.get("completion_tokens").asInt();
                }

                String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                        ? choice.get("finish_reason").asText() : null;

                if (finishReason != null && !"stop".equals(finishReason)) {
                    String reason = "tool_calls".equals(finishReason) ? "tool_use" : finishReason;
                    queue.add(new ApiStreamEvent.TurnComplete(
                            new UsageSnapshot(inputTokens, outputTokens), reason));
                    return;
                }

                JsonNode delta = choice.get("delta");
                if (delta == null) return;

                // Text delta
                if (delta.has("content") && !delta.get("content").isNull()) {
                    String text = delta.get("content").asText();
                    if (!text.isEmpty()) {
                        queue.add(new ApiStreamEvent.ContentDelta(text, 0));
                    }
                }

                // Tool call delta
                if (delta.has("tool_calls")) {
                    JsonNode toolCalls = delta.get("tool_calls");
                    for (JsonNode tc : toolCalls) {
                        String toolIndex = tc.has("index") ? tc.get("index").asText() : "0";

                        // Tool use start
                        if (tc.has("id") && !tc.get("id").isNull()) {
                            currentToolId = tc.get("id").asText();
                        }
                        JsonNode func = tc.get("function");
                        if (func != null) {
                            if (func.has("name") && !func.get("name").isNull()) {
                                currentToolName = func.get("name").asText();
                                toolInputBuffer.setLength(0);
                                queue.add(new ApiStreamEvent.ToolUseStart(currentToolId, currentToolName));
                            }
                            if (func.has("arguments") && !func.get("arguments").isNull()) {
                                String args = func.get("arguments").asText();
                                toolInputBuffer.append(args);
                                queue.add(new ApiStreamEvent.ToolUseInputDelta(currentToolId, args));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                LOG.warning("Failed to parse OpenAI SSE event: " + e.getMessage());
            }
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            if (response != null) {
                int code = response.code();
                String msg = "HTTP " + code + ": " + response.message();
                String errorCode = code == 401 ? "auth_error"
                        : code == 429 ? "rate_limit_error"
                        : code >= 500 ? "server_error" : "request_error";
                queue.add(new ApiStreamEvent.ErrorEvent(errorCode, msg));
            } else {
                queue.add(new ApiStreamEvent.ErrorEvent("connection_error",
                        t != null ? t.getMessage() : "SSE connection failed"));
            }
            queue.add(null);
        }

        @Override
        public void onClosed(EventSource eventSource) {
            queue.add(null);
        }
    }
}
