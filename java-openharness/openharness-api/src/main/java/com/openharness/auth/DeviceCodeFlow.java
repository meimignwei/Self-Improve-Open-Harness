package com.openharness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * GitHub OAuth Device Flow authentication.
 * Java equivalent of Python's Copilot OAuth device flow.
 */
public class DeviceCodeFlow implements AuthFlow {

    private static final Logger LOG = Logger.getLogger(DeviceCodeFlow.class.getName());
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String CLIENT_ID = "Iv1.b507a08c87ecf755";

    private final HttpClient httpClient;

    public DeviceCodeFlow() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public CredentialStorage.StoredCredential authenticate() throws AuthException {
        // Check for existing stored credential first
        var existing = CredentialStorage.loadAll().get("github_copilot");
        if (existing != null && "configured".equals(existing.state())) {
            return existing;
        }

        try {
            // Step 1: Get device code
            String deviceBody = "client_id=" + CLIENT_ID + "&scope=read:user";
            HttpRequest deviceRequest = HttpRequest.newBuilder()
                    .uri(URI.create(DEVICE_CODE_URL))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(deviceBody))
                    .build();

            HttpResponse<String> deviceResponse = httpClient.send(deviceRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (deviceResponse.statusCode() != 200) {
                throw new AuthException("Failed to get device code: HTTP " + deviceResponse.statusCode());
            }

            JsonNode deviceData = OpenHarnessObjectMapper.get().readTree(deviceResponse.body());
            String deviceCode = deviceData.get("device_code").asText();
            String userCode = deviceData.get("user_code").asText();
            String verificationUri = deviceData.get("verification_uri").asText();
            int interval = deviceData.has("interval") ? deviceData.get("interval").asInt(5) : 5;

            LOG.info("Please visit " + verificationUri + " and enter code: " + userCode);

            // Step 2: Poll for access token
            String grantType = "urn:ietf:params:oauth:grant-type:device_code";
            String tokenBody = "client_id=" + CLIENT_ID
                    + "&device_code=" + deviceCode
                    + "&grant_type=" + grantType;

            for (int attempt = 0; attempt < 60; attempt++) {
                Thread.sleep(Duration.ofSeconds(interval));

                HttpRequest tokenRequest = HttpRequest.newBuilder()
                        .uri(URI.create(ACCESS_TOKEN_URL))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                        .build();

                HttpResponse<String> tokenResponse = httpClient.send(tokenRequest,
                        HttpResponse.BodyHandlers.ofString());

                if (tokenResponse.statusCode() == 200) {
                    JsonNode tokenData = OpenHarnessObjectMapper.get().readTree(tokenResponse.body());
                    if (tokenData.has("access_token")) {
                        String accessToken = tokenData.get("access_token").asText();
                        var cred = new CredentialStorage.StoredCredential(
                                "oauth_device", accessToken, "github_device_flow", "configured");
                        CredentialStorage.store("github_copilot", cred);
                        return cred;
                    }
                    if (tokenData.has("error")) {
                        String error = tokenData.get("error").asText();
                        if ("authorization_pending".equals(error)) {
                            continue; // Keep polling
                        }
                        if ("slow_down".equals(error)) {
                            interval = Math.min(interval + 5, 60);
                            continue;
                        }
                        if ("expired_token".equals(error)) {
                            throw new AuthException("Device code expired. Please try again.");
                        }
                    }
                }
            }
            throw new AuthException("Authentication timed out.");

        } catch (IOException | InterruptedException e) {
            throw new AuthException("Device flow failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAuthenticated() {
        var cred = CredentialStorage.loadAll().get("github_copilot");
        return cred != null && "configured".equals(cred.state());
    }

    @Override
    public CredentialStorage.StoredCredential refresh() throws AuthException {
        // GitHub OAuth tokens don't expire for device flow
        return authenticate();
    }
}
