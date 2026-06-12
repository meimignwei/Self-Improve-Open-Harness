package com.openharness.observability;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lightweight HTTP server for health checks and Prometheus metrics.
 * Exposes /health (liveness), /ready (readiness), and /metrics endpoints.
 */
public class HealthServer {

    private static final Logger LOG = Logger.getLogger(HealthServer.class.getName());

    private final OpenHarnessMeters meters;
    private final int port;
    private volatile boolean running;
    private com.sun.net.httpserver.HttpServer server;

    public HealthServer(OpenHarnessMeters meters, int port) {
        this.meters = meters;
        this.port = port;
    }

    /**
     * Start the health server on the configured port.
     */
    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> {
            String response = healthJson();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/ready", exchange -> {
            String response = readyJson();
            int status = response.contains("\"DOWN\"") ? 503 : 200;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.createContext("/metrics", exchange -> {
            String response = meters.scrape();
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        running = true;
        LOG.info("Health server started on port " + port);
    }

    public void stop() {
        running = false;
        if (server != null) {
            server.stop(2);
        }
    }

    public boolean isRunning() { return running; }

    private String healthJson() {
        var mapper = OpenHarnessObjectMapper.get();
        ObjectNode root = mapper.createObjectNode();
        root.put("status", "UP");
        ObjectNode components = mapper.createObjectNode();
        components.put("jvm", "UP");
        long freeSpace = java.io.File.listRoots()[0].getFreeSpace();
        components.put("disk", freeSpace > 100_000_000 ? "UP" : "DOWN");
        root.set("components", components);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"status\":\"UP\"}";
        }
    }

    private String readyJson() {
        var mapper = OpenHarnessObjectMapper.get();
        ObjectNode root = mapper.createObjectNode();
        root.put("status", "UP");
        root.put("ready", true);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"status\":\"UP\",\"ready\":true}";
        }
    }
}
