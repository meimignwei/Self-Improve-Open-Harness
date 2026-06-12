package com.openharness;

import com.openharness.cli.MainCommand;
import com.openharness.observability.HealthServer;
import com.openharness.observability.ObservabilityBootstrap;
import com.openharness.observability.OpenHarnessMeters;
import picocli.CommandLine;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fat JAR entry point. Bootstraps observability, starts health server,
 * then delegates to Picocli MainCommand.
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {}

    public static void main(String[] args) {
        var obs = new ObservabilityBootstrap(
                System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", null));

        HealthServer healthServer = null;
        try {
            healthServer = new HealthServer(obs.meters(), 8080);
            healthServer.start();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Health server failed to start: " + e.getMessage());
        }

        var finalHealth = healthServer;
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            if (finalHealth != null && finalHealth.isRunning()) {
                finalHealth.stop();
            }
            obs.shutdown();
        }));

        int exitCode = new CommandLine(new MainCommand()).execute(args);

        if (finalHealth != null && finalHealth.isRunning()) {
            finalHealth.stop();
        }
        obs.shutdown();
        System.exit(exitCode);
    }
}
