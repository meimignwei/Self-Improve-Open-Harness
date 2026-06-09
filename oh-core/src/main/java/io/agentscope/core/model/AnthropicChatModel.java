package io.agentscope.core.model;

public class AnthropicChatModel implements Model {

    private final String apiKey;
    private final String modelName;

    private AnthropicChatModel(Builder builder) {
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String modelName;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public AnthropicChatModel build() {
            return new AnthropicChatModel(this);
        }
    }
}
