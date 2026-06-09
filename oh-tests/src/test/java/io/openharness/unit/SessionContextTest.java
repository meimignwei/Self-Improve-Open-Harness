package io.openharness.unit;

import io.openharness.core.config.Settings;
import io.openharness.core.session.SessionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SessionContextTest {

    @TempDir
    Path workspaceDir;

    @Test
    void shouldBindAllFieldsCorrectly() {
        Settings settings = Settings.defaults();
        Instant now = Instant.now();

        SessionContext ctx = SessionContext.builder()
                .sessionId("abc-123")
                .workspaceDir(workspaceDir)
                .settings(settings)
                .model("claude-sonnet-4-6")
                .createdAt(now)
                .build();

        assertThat(ctx.getSessionId()).isEqualTo("abc-123");
        assertThat(ctx.getWorkspaceDir()).isEqualTo(workspaceDir);
        assertThat(ctx.getSettings()).isSameAs(settings);
        assertThat(ctx.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(ctx.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void shouldUpdateModelAfterConstruction() {
        SessionContext ctx = SessionContext.builder()
                .sessionId("test")
                .workspaceDir(workspaceDir)
                .settings(Settings.defaults())
                .model("claude-sonnet-4-6")
                .build();

        ctx.setModel("claude-opus-4-7");
        assertThat(ctx.getModel()).isEqualTo("claude-opus-4-7");
    }
}
