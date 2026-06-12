package com.openharness.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Named provider workflow configuration.
 * Java equivalent of Python's ProviderProfile Pydantic model.
 */
public class ProviderProfile {

    private String label;
    private String provider;
    private String apiFormat;
    private String authSource;
    private String defaultModel;
    private String baseUrl = null;
    private String lastModel = null;
    private String credentialSlot = null;
    private List<String> allowedModels = new ArrayList<>();
    private Integer contextWindowTokens = null;
    private Integer autoCompactThresholdTokens = null;

    public ProviderProfile() {}

    public ProviderProfile(String label, String provider, String apiFormat,
                           String authSource, String defaultModel) {
        this.label = label;
        this.provider = provider;
        this.apiFormat = apiFormat;
        this.authSource = authSource;
        this.defaultModel = defaultModel;
    }

    public String resolvedModel() {
        String model = (lastModel != null && !lastModel.isBlank()) ? lastModel : defaultModel;
        return model != null ? model : defaultModel;
    }

    public String label() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String provider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String apiFormat() { return apiFormat; }
    public void setApiFormat(String apiFormat) { this.apiFormat = apiFormat; }

    public String authSource() { return authSource; }
    public void setAuthSource(String authSource) { this.authSource = authSource; }

    public String defaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public String baseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String lastModel() { return lastModel; }
    public void setLastModel(String lastModel) { this.lastModel = lastModel; }

    public String credentialSlot() { return credentialSlot; }
    public void setCredentialSlot(String credentialSlot) { this.credentialSlot = credentialSlot; }

    public List<String> allowedModels() { return allowedModels; }
    public void setAllowedModels(List<String> allowedModels) { this.allowedModels = allowedModels; }

    public Integer contextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(Integer contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }

    public Integer autoCompactThresholdTokens() { return autoCompactThresholdTokens; }
    public void setAutoCompactThresholdTokens(Integer autoCompactThresholdTokens) { this.autoCompactThresholdTokens = autoCompactThresholdTokens; }
}
