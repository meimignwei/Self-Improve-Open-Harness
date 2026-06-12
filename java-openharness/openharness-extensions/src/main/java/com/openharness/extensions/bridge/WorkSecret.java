package com.openharness.extensions.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;

import java.util.Base64;

/**
 * Base64URL-encoded JSON secret for SDK connection authentication.
 * Java equivalent of Python bridge/work_secret.py.
 */
public record WorkSecret(int version, String sessionIngressToken, String apiBaseUrl) {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    public static String encode(WorkSecret secret) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(secret);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode work secret", e);
        }
    }

    public static WorkSecret decode(String encoded) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(encoded);
            return MAPPER.readValue(json, WorkSecret.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode work secret", e);
        }
    }

    public static String buildSdkUrl(WorkSecret secret, String host, int port) {
        String encoded = encode(secret);
        return String.format("ws://%s:%d/v2/sdk?secret=%s", host, port, encoded);
    }
}
