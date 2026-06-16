package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Leader-Worker permission synchronization protocol via file-based mailbox.
 * Java equivalent of Python swarm/permission_sync.py.
 */
public class PermissionSyncProtocol {

    private static final Logger logger = LoggerFactory.getLogger(PermissionSyncProtocol.class);
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    private final String teamName;
    private final FileMailbox mailbox;
    private final long defaultTimeoutMs;
    private final Map<String, CompletableFuture<SwarmPermissionResponse>> pendingRequests = new ConcurrentHashMap<>();

    public PermissionSyncProtocol(String teamName, FileMailbox mailbox, long defaultTimeoutMs) {
        this.teamName = teamName;
        this.mailbox = mailbox;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public PermissionSyncProtocol(String teamName, FileMailbox mailbox) {
        this(teamName, mailbox, 30_000);
    }

    // ------------------------------------------------------------------
    // Worker-side: request permission from leader
    // ------------------------------------------------------------------

    public SwarmPermissionRequest createPermissionRequest(String workerId, String toolName,
                                                           Map<String, Object> arguments) {
        return new SwarmPermissionRequest(
                UUID.randomUUID().toString(),
                workerId,
                toolName,
                arguments,
                System.currentTimeMillis() / 1000.0);
    }

    public CompletableFuture<SwarmPermissionResponse> sendPermissionRequest(
            SwarmPermissionRequest request, long timeoutMs) {

        CompletableFuture<SwarmPermissionResponse> future = new CompletableFuture<>();
        pendingRequests.put(request.requestId, future);

        // Write request to mailbox
        FileMailbox.MailboxMessage msg = new FileMailbox.MailboxMessage(
                request.requestId,
                "permission_request",
                request.workerId,
                "leader@" + teamName,
                request);
        mailbox.write(msg);

        // Set up timeout
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS).execute(() -> {
            if (!future.isDone()) {
                future.complete(new SwarmPermissionResponse(
                        request.requestId, request.workerId, "denied", "timeout", null));
            }
        });

        return future;
    }

    public CompletableFuture<SwarmPermissionResponse> sendPermissionRequest(
            SwarmPermissionRequest request) {
        return sendPermissionRequest(request, defaultTimeoutMs);
    }

    // ------------------------------------------------------------------
    // Leader-side: handle incoming permission requests
    // ------------------------------------------------------------------

    public void handlePermissionRequests(Consumer<SwarmPermissionRequest> handler) {
        FileMailbox leaderMailbox = new FileMailbox(teamName, "leader");
        List<FileMailbox.MailboxMessage> messages = leaderMailbox.readAll(true);

        for (FileMailbox.MailboxMessage msg : messages) {
            if ("permission_request".equals(msg.type)) {
                try {
                    SwarmPermissionRequest request = MAPPER.convertValue(
                            msg.payload, SwarmPermissionRequest.class);
                    if (request != null && !pendingRequests.containsKey(request.requestId)) {
                        handler.accept(request);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse permission request from {}", msg.sender, e);
                }
            }
        }
    }

    public void sendPermissionResponse(SwarmPermissionResponse response) {
        FileMailbox.MailboxMessage msg = new FileMailbox.MailboxMessage(
                response.requestId,
                "permission_response",
                "leader",
                response.workerId + "@" + teamName,
                response);
        mailbox.write(msg);
        logger.debug("Sent permission response: {} -> {}", response.requestId, response.decision);
    }

    public SwarmPermissionResponse pollPermissionResponse(String requestId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        FileMailbox workerMailbox = new FileMailbox(teamName, requestId);

        while (System.currentTimeMillis() < deadline) {
            List<FileMailbox.MailboxMessage> messages = workerMailbox.readAll(true);
            for (FileMailbox.MailboxMessage msg : messages) {
                if ("permission_response".equals(msg.type)) {
                    try {
                        SwarmPermissionResponse response = MAPPER.convertValue(
                                msg.payload, SwarmPermissionResponse.class);
                        if (response != null && response.requestId.equals(requestId)) {
                            workerMailbox.markRead(msg.id);
                            return response;
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to parse permission response", e);
                    }
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new SwarmPermissionResponse(requestId, null, "denied", "timeout", null);
    }

    // ------------------------------------------------------------------
    // Types (Python SwarmPermissionRequest / SwarmPermissionResponse)
    // ------------------------------------------------------------------

    public static class SwarmPermissionRequest {
        @JsonProperty("request_id") public String requestId;
        @JsonProperty("worker_id") public String workerId;
        @JsonProperty("tool_name") public String toolName;
        @JsonProperty("arguments") public Map<String, Object> arguments;
        @JsonProperty("timestamp") public double timestamp;

        public SwarmPermissionRequest() {}

        public SwarmPermissionRequest(String requestId, String workerId, String toolName,
                                       Map<String, Object> arguments, double timestamp) {
            this.requestId = requestId;
            this.workerId = workerId;
            this.toolName = toolName;
            this.arguments = arguments;
            this.timestamp = timestamp;
        }
    }

    public static class SwarmPermissionResponse {
        @JsonProperty("request_id") public String requestId;
        @JsonProperty("worker_id") public String workerId;
        @JsonProperty("decision") public String decision; // "approved" | "denied"
        @JsonProperty("reason") public String reason;
        @JsonProperty("metadata") public Map<String, Object> metadata;

        public SwarmPermissionResponse() {}

        public SwarmPermissionResponse(String requestId, String workerId, String decision,
                                        String reason, Map<String, Object> metadata) {
            this.requestId = requestId;
            this.workerId = workerId;
            this.decision = decision;
            this.reason = reason;
            this.metadata = metadata;
        }
    }
}
