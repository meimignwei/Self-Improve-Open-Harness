package com.openharness.api;

import com.openharness.common.ApiStreamEvent;
import com.openharness.common.ConversationMessage;

import java.util.List;
import java.util.stream.Stream;

/**
 * Unified streaming interface for all LLM providers.
 * Java equivalent of Python's SupportsStreamingMessages protocol.
 */
public interface StreamingApiClient {

    /**
     * Send messages and return a stream of API events.
     */
    Stream<ApiStreamEvent> streamMessages(
            String model,
            String systemPrompt,
            List<ConversationMessage> messages,
            List<ToolDefinition> tools,
            StreamOptions options);

    /**
     * Get provider metadata for UI and diagnostics.
     */
    ProviderInfo getProviderInfo();
}
