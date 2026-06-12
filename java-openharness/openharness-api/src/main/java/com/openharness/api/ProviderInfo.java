package com.openharness.api;

/**
 * Resolved provider metadata for UI and diagnostics.
 * Java equivalent of Python's ProviderInfo dataclass.
 */
public record ProviderInfo(
        String name,
        String authKind,
        boolean voiceSupported,
        String voiceReason) {

    public static ProviderInfo apiKey(String name) {
        return new ProviderInfo(name, "api_key", false,
                "voice mode is not wired for OpenAI-compatible providers in this build");
    }

    public static ProviderInfo oauth(String name) {
        return new ProviderInfo(name, "oauth_device", false,
                "voice mode is not supported for this provider");
    }
}
