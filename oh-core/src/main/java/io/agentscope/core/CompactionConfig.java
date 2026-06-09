package io.agentscope.core;

public class CompactionConfig {

    private final int triggerMessages;
    private final int keepMessages;

    private CompactionConfig(Builder builder) {
        this.triggerMessages = builder.triggerMessages;
        this.keepMessages = builder.keepMessages;
    }

    public int getTriggerMessages() {
        return triggerMessages;
    }

    public int getKeepMessages() {
        return keepMessages;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int triggerMessages = 30;
        private int keepMessages = 10;

        public Builder triggerMessages(int triggerMessages) {
            this.triggerMessages = triggerMessages;
            return this;
        }

        public Builder keepMessages(int keepMessages) {
            this.keepMessages = keepMessages;
            return this;
        }

        public CompactionConfig build() {
            return new CompactionConfig(this);
        }
    }
}
