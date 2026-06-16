package com.openharness.extensions.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkSecretTest {

    @Test
    void encodeProducesNonEmptyString() {
        var secret = new WorkSecret(1, "my-session-token-12345", "https://api.example.com");
        String encoded = WorkSecret.encode(secret);

        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());
    }

    @Test
    void decodeShouldRejectWrongVersion() {
        var secret = new WorkSecret(99, "token", "https://api.example.com");
        String encoded = WorkSecret.encode(secret);

        // Exceptions are wrapped in RuntimeException by decode()
        assertThrows(RuntimeException.class, () -> WorkSecret.decode(encoded));
    }

    @Test
    void buildSdkUrlShouldUseWsForLocalhost() {
        String url = WorkSecret.buildSdkUrl("http://localhost:8080", "session-123");
        assertTrue(url.startsWith("ws://"));
        assertTrue(url.contains("session-123"));
    }

    @Test
    void buildSdkUrlShouldUseWssForRemote() {
        String url = WorkSecret.buildSdkUrl("https://api.example.com", "session-456");
        assertTrue(url.startsWith("wss://"));
        assertTrue(url.contains("session-456"));
    }

    @Test
    void buildSdkUrlShouldUseV2ForLocalhost() {
        String url = WorkSecret.buildSdkUrl("http://127.0.0.1:3000", "sess");
        assertTrue(url.contains("/v2/"));
    }

    @Test
    void buildSdkUrlShouldUseV1ForRemote() {
        String url = WorkSecret.buildSdkUrl("https://remote.example.com", "sess");
        assertTrue(url.contains("/v1/"));
    }
}
