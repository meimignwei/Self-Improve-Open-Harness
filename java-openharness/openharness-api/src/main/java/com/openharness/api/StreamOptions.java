package com.openharness.api;

/**
 * Streaming options for LLM API calls.
 */
public record StreamOptions(
        int maxTokens,
        double temperature,
        String systemPrompt,
        boolean enableTools) {

    public static StreamOptions defaults() {
        return new StreamOptions(16384, 0.0, null, true);
    }

    public StreamOptions withMaxTokens(int maxTokens) {
        return new StreamOptions(maxTokens, temperature, systemPrompt, enableTools);
    }

    public StreamOptions withSystemPrompt(String systemPrompt) {
        return new StreamOptions(maxTokens, temperature, systemPrompt, enableTools);
    }
}
