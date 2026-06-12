package com.openharness.auth;

import com.openharness.config.ProviderProfile;
import com.openharness.config.Settings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central authentication manager supporting API key, OAuth device flow, and browser flows.
 * Java equivalent of Python's auth/manager.py.
 * <p>
 * Supports 12+ providers and 3 auth source types: api_key, oauth_device, external_oauth.
 */
public class AuthManager {

    private static final Logger LOG = Logger.getLogger(AuthManager.class.getName());
    private static final Map<String, AuthFlow> FLOW_CACHE = new ConcurrentHashMap<>();

    /**
     * Resolve credentials for the active provider profile.
     */
    public static CredentialStorage.StoredCredential resolveAuth(Settings settings) throws AuthFlow.AuthException {
        ProviderProfile profile = resolveProfile(settings);
        AuthFlow flow = getOrCreateFlow(profile);
        return flow.authenticate();
    }

    /**
     * Check if the active provider has valid credentials configured.
     */
    public static boolean isAuthenticated(Settings settings) {
        try {
            ProviderProfile profile = resolveProfile(settings);
            AuthFlow flow = getOrCreateFlow(profile);
            return flow.isAuthenticated();
        } catch (Exception e) {
            return false;
        }
    }

    private static ProviderProfile resolveProfile(Settings settings) {
        String activeProfile = settings.activeProfile();
        Map<String, ProviderProfile> profiles = settings.mergedProfiles();
        return profiles.getOrDefault(activeProfile,
                profiles.get("claude-api"));
    }

    private static AuthFlow getOrCreateFlow(ProviderProfile profile) {
        String key = profile.provider() + ":" + profile.authSource();
        return FLOW_CACHE.computeIfAbsent(key, k -> createFlow(profile));
    }

    private static AuthFlow createFlow(ProviderProfile profile) {
        String authSource = profile.authSource();
        if (authSource == null || authSource.isBlank()) {
            authSource = "api_key";
        }

        return switch (authSource) {
            case "claude_subscription", "codex_subscription" ->
                    // External CLI-managed OAuth tokens
                    new ApiKeyFlow(profile.provider(),
                            "ANTHROPIC_AUTH_TOKEN");

            case "copilot_oauth" -> new DeviceCodeFlow();

            default ->
                    // Standard API key flow
                    new ApiKeyFlow(profile.provider(),
                            envKeyForProvider(profile.provider()));
        };
    }

    private static String envKeyForProvider(String provider) {
        return switch (provider) {
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "gemini" -> "GEMINI_API_KEY";
            case "dashscope" -> "DASHSCOPE_API_KEY";
            case "moonshot" -> "MOONSHOT_API_KEY";
            case "minimax" -> "MINIMAX_API_KEY";
            case "groq" -> "GROQ_API_KEY";
            case "mistral" -> "MISTRAL_API_KEY";
            default -> provider.toUpperCase() + "_API_KEY";
        };
    }
}
