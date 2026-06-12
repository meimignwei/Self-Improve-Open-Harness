package com.openharness.extensions.swarm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.config.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Leader-Worker permission synchronization protocol.
 * Worker agents send permission requests that the Leader (UI) approves or denies.
 * Java equivalent of Python swarm/permission_sync.py.
 */
public class PermissionSyncProtocol {

    private final Path syncDir;
    private final FileMailbox mailbox;
    private final Duration defaultTimeout;

    public PermissionSyncProtocol(Path syncDir, FileMailbox mailbox, Duration defaultTimeout) {
        this.syncDir = syncDir;
        this.mailbox = mailbox;
        this.defaultTimeout = defaultTimeout;
        try {
            Files.createDirectories(syncDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create sync dir: " + syncDir, e);
        }
    }

    public PermissionSyncProtocol(Path syncDir, FileMailbox mailbox) {
        this(syncDir, mailbox, Duration.ofSeconds(30));
    }

    // ── Worker-side: request permission ──

    public CompletableFuture<PermissionResolution> requestPermission(
            String workerId, String toolName, JsonNode arguments) {
        return requestPermission(workerId, toolName, arguments, defaultTimeout);
    }

    public CompletableFuture<PermissionResolution> requestPermission(
            String workerId, String toolName, JsonNode arguments, Duration timeout) {

        String requestId = UUID.randomUUID().toString();
        PermissionRequest request = new PermissionRequest(
                requestId, workerId, toolName, arguments, Instant.now());

        Path requestFile = syncDir.resolve(requestId + "_req.json");
        AtomicFileWriter.writeJson(requestFile, request);

        var payload = OpenHarnessObjectMapper.get().createObjectNode()
                .put("type", "permission_request")
                .put("request_id", requestId)
                .put("worker_id", workerId);
        mailbox.send("leader", FileMailbox.MailboxMessage.of(
                workerId, "leader", "permission_request", payload));

        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                Path resolutionFile = syncDir.resolve(requestId + "_res.json");
                if (Files.exists(resolutionFile)) {
                    PermissionResolution res = AtomicFileWriter.readJson(
                            resolutionFile, PermissionResolution.class);
                    if (res != null) {
                        try { Files.deleteIfExists(resolutionFile); } catch (IOException ignored) {}
                        try { Files.deleteIfExists(requestFile); } catch (IOException ignored) {}
                        return res;
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return new PermissionResolution(requestId, "denied",
                    "timeout", null, Instant.now());
        });
    }

    // ── Leader-side: process permission requests ──

    public void processPermissionRequests(Consumer<PermissionRequest> handler) {
        try (var files = Files.newDirectoryStream(syncDir, "*_req.json")) {
            for (Path f : files) {
                try {
                    PermissionRequest request = AtomicFileWriter.readJson(
                            f, PermissionRequest.class);
                    if (request != null && !Files.exists(
                            syncDir.resolve(request.requestId() + "_res.json"))) {
                        handler.accept(request);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to process request file: " + f);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list requests", e);
        }
    }

    public void resolve(String requestId, String decision, String reason, Map<String, Object> metadata) {
        PermissionResolution resolution = new PermissionResolution(
                requestId, decision, reason, metadata, Instant.now());
        Path resolutionFile = syncDir.resolve(requestId + "_res.json");
        AtomicFileWriter.writeJson(resolutionFile, resolution);
    }

    // ── Types ──

    public record PermissionRequest(
            String requestId,
            String workerId,
            String toolName,
            JsonNode arguments,
            Instant timestamp
    ) {}

    public record PermissionResolution(
            String requestId,
            String decision,  // "approved" | "denied"
            String reason,
            Map<String, Object> metadata,
            Instant timestamp
    ) {}
}
