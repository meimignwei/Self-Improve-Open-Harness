package io.openharness.core.observability;

import java.util.LinkedHashMap;
import java.util.Map;

public class HealthChecker {

    public enum Status { UP, DOWN, DEGRADED }

    public record HealthReport(Map<String, Status> checks, Status overall) {
        public HealthReport(Map<String, Status> checks) {
            this(checks, checks.values().stream()
                .allMatch(s -> s == Status.UP) ? Status.UP : Status.DEGRADED);
        }

        public boolean isHealthy() { return overall == Status.UP; }
    }

    public HealthReport check() {
        Map<String, Status> report = new LinkedHashMap<>();
        report.put("workspace", checkWorkspace());
        report.put("config", checkConfig());
        report.put("api", checkApiConnectivity());
        report.put("sandbox", checkSandbox());
        report.put("db", checkDatabase());
        report.put("disk", checkDiskSpace());
        report.put("logs", checkLogDir());
        return new HealthReport(report);
    }

    private Status checkWorkspace() { return Status.UP; }
    private Status checkConfig() { return Status.UP; }
    private Status checkApiConnectivity() { return Status.UP; }
    private Status checkSandbox() { return Status.UP; }
    private Status checkDatabase() { return Status.UP; }
    private Status checkDiskSpace() { return Status.UP; }
    private Status checkLogDir() { return Status.UP; }
}
