package com.openharness.api;

/**
 * One LLM provider's metadata.
 * Java equivalent of Python's ProviderSpec dataclass.
 */
public record ProviderSpec(
        // Identity
        String name,
        String[] keywords,
        String envKey,
        String displayName,

        // Routing
        String backendType,
        String defaultBaseUrl,

        // Auto-detection signals
        String detectByKeyPrefix,
        String detectByBaseKeyword,

        // Classification flags
        boolean isGateway,
        boolean isLocal,
        boolean isOauth) {

    public String label() {
        return displayName != null && !displayName.isBlank()
                ? displayName
                : capitalize(name);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
