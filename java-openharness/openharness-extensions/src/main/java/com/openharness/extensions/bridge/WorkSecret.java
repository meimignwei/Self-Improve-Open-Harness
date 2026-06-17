package com.openharness.extensions.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Base64URL-encoded JSON secret for SDK/bridge connection authentication.
 * Java equivalent of Python bridge/work_secret.py.
 */
public record WorkSecret(
        @JsonProperty("version") int version,
        @JsonProperty("session_ingress_token") String sessionIngressToken,
        @JsonProperty("api_base_url") String apiBaseUrl) {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    public static String encode(WorkSecret secret) {
        try {
            byte[] json = MAPPER.writeValueAsString(Map.of(
                    "version", secret.version(),
                    "session_ingress_token", secret.sessionIngressToken(),
                    "api_base_url", secret.apiBaseUrl()
            )).getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode work secret", e);
        }
    }

    public static WorkSecret decode(String encoded) {
        try {
            String padding = "=".repeat((4 - encoded.length() % 4) % 4);
            byte[] raw = Base64.getUrlDecoder().decode(encoded + padding);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = MAPPER.readValue(raw, Map.class);

            int version = ((Number) data.get("version")).intValue();
            if (version != 1) {
                throw new IllegalArgumentException("Unsupported work secret version: " + version);
            }

            String token = (String) data.get("session_ingress_token");
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("Invalid work secret: missing session_ingress_token");
            }

            String url = (String) data.get("api_base_url");
            if (url == null) {
                throw new IllegalArgumentException("Invalid work secret: missing api_base_url");
            }

            return new WorkSecret(version, token, url);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode work secret", e);
        }
    }

    /**
     * Build a session ingress WebSocket URL.
     * Java equivalent of Python build_sdk_url().
     */
    public static String buildSdkUrl(String apiBaseUrl, String sessionId) {
        boolean isLocal = apiBaseUrl.contains("localhost") || apiBaseUrl.contains("127.0.0.1");
        String protocol = isLocal ? "ws" : "wss";
        String version = isLocal ? "v2" : "v1";
        String host = apiBaseUrl.replace("https://", "").replace("http://", "").replaceAll("/$", "");
        return protocol + "://" + host + "/" + version + "/session_ingress/ws/" + sessionId;
    }
}
