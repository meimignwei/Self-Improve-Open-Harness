package io.openharness.core.session;

import io.openharness.core.config.Settings;

import java.nio.file.Path;
import java.time.Instant;

public class SessionContext {

    private final String sessionId;
    private final Path workspaceDir;
    private final Settings settings;
    private String model;
    private final Instant createdAt;

    private SessionContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.workspaceDir = builder.workspaceDir;
        this.settings = builder.settings;
        this.model = builder.model;
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public Path getWorkspaceDir() { return workspaceDir; }
    public Settings getSettings() { return settings; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Instant getCreatedAt() { return createdAt; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private Path workspaceDir;
        private Settings settings;
        private String model;
        private Instant createdAt;

        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder workspaceDir(Path workspaceDir) { this.workspaceDir = workspaceDir; return this; }
        public Builder settings(Settings settings) { this.settings = settings; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public SessionContext build() {
            return new SessionContext(this);
        }
    }
}
