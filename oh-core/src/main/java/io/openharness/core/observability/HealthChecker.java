package io.openharness.core.observability;

import io.openharness.core.config.Settings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

public class HealthChecker {

    public enum Status { UP, DOWN, DEGRADED }

    public record HealthReport(Map<String, Status> checks, Status overall) {
        public HealthReport(Map<String, Status> checks) {
            this(checks, checks.values().stream()
                .allMatch(s -> s == Status.UP) ? Status.UP : Status.DEGRADED);
        }

        public boolean isHealthy() { return overall == Status.UP; }
    }

    private final Settings settings;
    private final DataSource dataSource;

    public HealthChecker(Settings settings) {
        this(settings, null);
    }

    public HealthChecker(Settings settings, DataSource dataSource) {
        this.settings = settings;
        this.dataSource = dataSource;
    }

    public HealthReport check() {
        Map<String, Status> report = new LinkedHashMap<>();
        report.put("workspace", checkWorkspace());
        report.put("config", checkConfig());
        report.put("db", checkDatabase());
        report.put("disk", checkDiskSpace());
        report.put("logs", checkLogDir());
        return new HealthReport(report);
    }

    private Status checkWorkspace() {
        String dir = settings.getWorkspaceDir();
        if (dir == null || dir.isBlank()) return Status.DOWN;
        Path path = Path.of(dir);
        if (Files.isDirectory(path) && Files.isWritable(path)) return Status.UP;
        return Status.DOWN;
    }

    private Status checkConfig() {
        if (settings.getModel() == null) return Status.DOWN;
        if (settings.getMaxTurns() <= 0) return Status.DEGRADED;
        return Status.UP;
    }

    private Status checkDatabase() {
        if (dataSource == null) return Status.DEGRADED;
        try (var conn = dataSource.getConnection()) {
            return conn.isValid(3) ? Status.UP : Status.DOWN;
        } catch (Exception e) {
            return Status.DOWN;
        }
    }

    private Status checkDiskSpace() {
        String dir = settings.getWorkspaceDir();
        if (dir == null) return Status.DEGRADED;
        File file = new File(dir);
        long free = file.getFreeSpace();
        return free > 100L * 1024 * 1024 ? Status.UP : Status.DEGRADED;
    }

    private Status checkLogDir() {
        Path logDir = Path.of(System.getProperty("user.home"), ".oh", "logs");
        try {
            Files.createDirectories(logDir);
            return Files.isWritable(logDir) ? Status.UP : Status.DOWN;
        } catch (Exception e) {
            return Status.DOWN;
        }
    }
}
