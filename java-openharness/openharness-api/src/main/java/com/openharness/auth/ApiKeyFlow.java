package com.openharness.auth;

import java.util.logging.Logger;

/**
 * API Key input authentication flow.
 * Java equivalent of Python's ApiKeyFlow.
 */
public class ApiKeyFlow implements AuthFlow {

    private static final Logger LOG = Logger.getLogger(ApiKeyFlow.class.getName());

    private final String providerName;
    private final String envKey;

    public ApiKeyFlow(String providerName, String envKey) {
        this.providerName = providerName;
        this.envKey = envKey;
    }

    @Override
    public CredentialStorage.StoredCredential authenticate() throws AuthException {
        // Check environment variable first
        String apiKey = System.getenv(envKey);
        if (apiKey != null && !apiKey.isBlank()) {
            var cred = new CredentialStorage.StoredCredential(
                    "api_key", apiKey, "env:" + envKey, "configured");
            CredentialStorage.store(providerName, cred);
            return cred;
        }

        // Check existing stored credential
        var stored = CredentialStorage.loadAll().get(providerName);
        if (stored != null) {
            return stored;
        }

        throw new AuthException("No API key found for " + providerName
                + ". Set the " + envKey + " environment variable.");
    }

    @Override
    public boolean isAuthenticated() {
        if (System.getenv(envKey) != null) return true;
        return CredentialStorage.loadAll().containsKey(providerName);
    }

    @Override
    public CredentialStorage.StoredCredential refresh() throws AuthException {
        // API keys don't expire — just re-authenticate
        return authenticate();
    }
}
