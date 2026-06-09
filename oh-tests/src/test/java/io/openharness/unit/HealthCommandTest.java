package io.openharness.unit;

import io.openharness.cli.commands.ConfigCommand;
import io.openharness.cli.commands.HealthCommand;
import io.openharness.cli.commands.ModelCommand;
import io.openharness.core.config.Settings;
import io.openharness.core.observability.HealthChecker;
import io.openharness.core.session.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthCommandTest {

    private Settings settings;
    private SessionContext ctx;

    @BeforeEach
    void setUp() {
        settings = Settings.defaults();
        settings.setModel("claude-sonnet-4-6");
        ctx = SessionContext.builder()
                .sessionId("test-session")
                .workspaceDir(Path.of("/tmp"))
                .settings(settings)
                .model("claude-sonnet-4-6")
                .build();
    }

    @Test
    void shouldReportAllHealthy() {
        settings.setWorkspaceDir(System.getProperty("java.io.tmpdir"));
        HealthChecker checker = new HealthChecker(settings);
        HealthCommand cmd = new HealthCommand(checker);

        cmd.execute(List.of()).block();

        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks()).containsKeys("workspace", "config", "db", "disk", "logs");
        assertThat(report.checks().get("config")).isEqualTo(HealthChecker.Status.UP);
        assertThat(report.checks().get("db")).isEqualTo(HealthChecker.Status.DEGRADED);
    }

    @Test
    void shouldReportDegradedWhenWorkspaceNotSet() {
        settings.setWorkspaceDir("/nonexistent/path/xyz");
        HealthChecker checker = new HealthChecker(settings);
        HealthCommand cmd = new HealthCommand(checker);

        cmd.execute(List.of()).block();

        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("workspace")).isEqualTo(HealthChecker.Status.DOWN);
        assertThat(report.overall()).isNotEqualTo(HealthChecker.Status.UP);
    }

    @Test
    void shouldViewConfigAndSwitchModel() {
        ConfigCommand configCmd = new ConfigCommand(settings);
        configCmd.execute(List.of()).block();

        assertThat(settings.getModel()).isEqualTo("claude-sonnet-4-6");

        ModelCommand modelCmd = new ModelCommand(ctx);
        modelCmd.execute(List.of("opus")).block();

        assertThat(ctx.getModel()).isEqualTo("claude-opus-4-7");

        modelCmd.execute(List.of("haiku")).block();
        assertThat(ctx.getModel()).isEqualTo("claude-haiku-4-5");
    }
}
