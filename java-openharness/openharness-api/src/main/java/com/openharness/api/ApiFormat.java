package com.openharness.api;

/**
 * API format determines which client backend to use.
 */
public enum ApiFormat {
    ANTHROPIC,      // Anthropic native Messages API
    OPENAI,         // OpenAI-compatible Chat Completions API
    COPILOT         // GitHub Copilot OAuth
}
