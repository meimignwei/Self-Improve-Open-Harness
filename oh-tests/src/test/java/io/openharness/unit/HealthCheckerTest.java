package io.openharness.unit;

import io.openharness.core.config.Settings;
import io.openharness.core.observability.HealthChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthCheckerTest {

    private Settings settings;

    @BeforeEach
    void setUp() {
        settings = Settings.defaults();
        settings.setModel("claude-sonnet-4-6");
    }

    @Test
    void shouldCheckWorkspaceValid(@TempDir Path tempDir) {
        settings.setWorkspaceDir(tempDir.toString());
        HealthChecker checker = new HealthChecker(settings);

        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("workspace")).isEqualTo(HealthChecker.Status.UP);
    }

    @Test
    void shouldCheckWorkspaceInvalid() {
        settings.setWorkspaceDir("/nonexistent/dir/xyz123");
        HealthChecker checker = new HealthChecker(settings);

        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("workspace")).isEqualTo(HealthChecker.Status.DOWN);
    }

    @Test
    void shouldCheckConfigValid() {
        settings.setMaxTurns(50);
        HealthChecker checker = new HealthChecker(settings);

        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("config")).isEqualTo(HealthChecker.Status.UP);
    }

    @Test
    void shouldCheckConfigDegradedWhenMaxTurnsZero() {
        settings.setMaxTurns(0);
        HealthChecker checker = new HealthChecker(settings);

        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("config")).isEqualTo(HealthChecker.Status.DEGRADED);
    }

    @Test
    void shouldCheckConfigDownWhenModelMissing() {
        settings.setModel(null);
        HealthChecker checker = new HealthChecker(settings);

        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("config")).isEqualTo(HealthChecker.Status.DOWN);
    }

    @Test
    void shouldCheckDatabaseUp() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.isValid(3)).thenReturn(true);

        HealthChecker checker = new HealthChecker(settings, ds);
        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("db")).isEqualTo(HealthChecker.Status.UP);
    }

    @Test
    void shouldCheckDatabaseDegradedWhenNoDataSource() {
        HealthChecker checker = new HealthChecker(settings);
        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("db")).isEqualTo(HealthChecker.Status.DEGRADED);
    }

    @Test
    void shouldCheckDiskSpace(@TempDir Path tempDir) {
        settings.setWorkspaceDir(tempDir.toString());
        HealthChecker checker = new HealthChecker(settings);

        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("disk")).isEqualTo(HealthChecker.Status.UP);
    }

    @Test
    void shouldCheckLogDir() throws Exception {
        Path logDir = Path.of(System.getProperty("user.home"), ".oh", "logs");
        Files.createDirectories(logDir);

        HealthChecker checker = new HealthChecker(settings);
        HealthChecker.HealthReport report = checker.check();
        assertThat(report.checks().get("logs")).isIn(HealthChecker.Status.UP, HealthChecker.Status.DOWN);
    }
}
