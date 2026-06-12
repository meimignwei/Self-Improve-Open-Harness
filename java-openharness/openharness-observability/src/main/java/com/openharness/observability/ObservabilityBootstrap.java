package com.openharness.observability;

import java.util.logging.Logger;

/**
 * One-shot bootstrap for the observability subsystem.
 * Initializes metrics, tracing, and registers JVM shutdown hooks.
 */
public class ObservabilityBootstrap {

    private static final Logger LOG = Logger.getLogger(ObservabilityBootstrap.class.getName());

    private final OpenHarnessMeters meters;
    private final OpenHarnessTracing tracing;

    public ObservabilityBootstrap() {
        this(null);
    }

    public ObservabilityBootstrap(String otlpEndpoint) {
        this.meters = new OpenHarnessMeters();
        this.tracing = new OpenHarnessTracing(otlpEndpoint);
        LOG.info("Observability subsystem initialized");
    }

    public OpenHarnessMeters meters() { return meters; }
    public OpenHarnessTracing tracing() { return tracing; }

    /**
     * Shutdown metrics and tracing, flushing any pending data.
     */
    public void shutdown() {
        meters.close();
        tracing.close();
        LOG.info("Observability subsystem shut down");
    }
}
