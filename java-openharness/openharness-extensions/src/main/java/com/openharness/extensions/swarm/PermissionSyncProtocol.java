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
import java.util.ArrayList;
import java.util.Comparator;
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

    // ------------------------------------------------------------------
    // File-based permission storage (Python pending/ / resolved/ directory system)
    // ------------------------------------------------------------------

    public static Path getPermissionDir(String teamName) {
        return FileMailbox.getTeamDir(teamName).resolve("permissions");
    }

    public static Path getPendingDir(String teamName) {
        return getPermissionDir(teamName).resolve("pending");
    }

    public static Path getResolvedDir(String teamName) {
        return getPermissionDir(teamName).resolve("resolved");
    }

    /**
     * Write a permission request atomically to the pending directory.
     */
    public static void writePermissionRequest(String teamName,
                                               com.fasterxml.jackson.databind.JsonNode request) {
        try {
            Path pendingDir = getPendingDir(teamName);
            Files.createDirectories(pendingDir);
            String requestId = request.has("request_id")
                    ? request.get("request_id").asText()
                    : UUID.randomUUID().toString();
            Path file = pendingDir.resolve(requestId + ".json");
            Path tmp = pendingDir.resolve(requestId + ".json.tmp");
            MAPPER.writeValue(tmp.toFile(), request);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("Failed to write permission request for team {}", teamName, e);
        }
    }

    /**
     * Read all pending permission requests, sorted oldest-first.
     */
    public static List<Map<String, Object>> readPendingPermissions(String teamName) {
        List<Map<String, Object>> results = new ArrayList<>();
        Path pendingDir = getPendingDir(teamName);
        if (!Files.exists(pendingDir)) return results;

        try (var files = Files.list(pendingDir).sorted(Comparator.comparing(Path::getFileName))) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> req = MAPPER.readValue(f.toFile(), Map.class);
                    results.add(req);
                } catch (IOException e) {
                    logger.warn("Failed to read pending permission: {}", f, e);
                }
            });
        } catch (IOException e) {
            logger.warn("Failed to list pending permissions for team {}", teamName, e);
        }
        return results;
    }

    /**
     * Resolve (accept/reject) a permission request.
     * Moves the file from pending/ to resolved/ with resolution data.
     */
    public static void resolvePermission(String teamName, String requestId,
                                          String decision, String feedback,
                                          Map<String, Object> permissionUpdates) {
        Path pendingFile = getPendingDir(teamName).resolve(requestId + ".json");
        Path resolvedDir = getResolvedDir(teamName);
        Path resolvedFile = resolvedDir.resolve(requestId + ".json");

        try {
            Files.createDirectories(resolvedDir);
            if (Files.exists(pendingFile)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = MAPPER.readValue(pendingFile.toFile(), Map.class);
                request.put("status", decision);
                request.put("resolved_by", "leader@" + teamName);
                request.put("resolved_at", String.valueOf(System.currentTimeMillis() / 1000.0));
                if (feedback != null) request.put("feedback", feedback);
                if (permissionUpdates != null) request.put("permission_updates", permissionUpdates);

                Path tmp = resolvedDir.resolve(requestId + ".json.tmp");
                MAPPER.writeValue(tmp.toFile(), request);
                Files.move(tmp, resolvedFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(pendingFile);
                logger.info("Resolved permission {}: {} for team {}", requestId, decision, teamName);
            }
        } catch (IOException e) {
            logger.error("Failed to resolve permission {} for team {}", requestId, teamName, e);
        }
    }

    /**
     * Clean up resolution files older than maxAgeSeconds.
     */
    public static void cleanupOldResolutions(String teamName, long maxAgeSeconds) {
        Path resolvedDir = getResolvedDir(teamName);
        if (!Files.exists(resolvedDir)) return;

        long cutoff = System.currentTimeMillis() / 1000 - maxAgeSeconds;
        try (var files = Files.list(resolvedDir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    long mtime = Files.getLastModifiedTime(f).toMillis() / 1000;
                    if (mtime < cutoff) {
                        Files.deleteIfExists(f);
                        logger.debug("Cleaned up old resolution: {}", f.getFileName());
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            logger.warn("Failed to clean old resolutions for team {}", teamName, e);
        }
    }

    /**
     * Delete a resolved permission after the worker processes it.
     */
    public static void deleteResolvedPermission(String teamName, String requestId) {
        Path resolvedFile = getResolvedDir(teamName).resolve(requestId + ".json");
        try { Files.deleteIfExists(resolvedFile); } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------
    // Role detection (Python is_team_leader / is_swarm_worker / get_leader_name)
    // ------------------------------------------------------------------

    public static boolean isTeamLeader() {
        String agentId = System.getenv("CLAUDE_CODE_AGENT_ID");
        return agentId == null || agentId.isBlank() || "team-lead".equals(agentId);
    }

    public static boolean isSwarmWorker() {
        String teamName = System.getenv("CLAUDE_CODE_TEAM_NAME");
        String agentId = System.getenv("CLAUDE_CODE_AGENT_ID");
        return teamName != null && !teamName.isBlank()
                && agentId != null && !agentId.isBlank()
                && !"team-lead".equals(agentId);
    }

    /**
     * Look up the leader's name from the team file.
     */
    public static String getLeaderName(String teamName) {
        Path teamFile = FileMailbox.getTeamDir(teamName).resolve("team.json");
        if (!Files.exists(teamFile)) return "team-lead";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> team = MAPPER.readValue(teamFile.toFile(), Map.class);
            Object leadName = team.get("lead_agent_name");
            if (leadName != null) return leadName.toString();
            return "team-lead";
        } catch (IOException e) {
            return "team-lead";
        }
    }

    /**
     * Check whether a tool is read-only (no permission prompt needed).
     */
    public static boolean isReadOnly(String toolName) {
        return switch (toolName) {
            case "file_read", "read_file", "read", "glob", "grep", "web_fetch", "web_search",
                 "task_get", "task_list", "task_output", "cron_list" -> true;
            default -> false;
        };
    }

    /**
     * Leader-side: handle an incoming permission request by evaluating
     * it with a PermissionChecker. Auto-approves read-only tools.
     *
     * @param request  the parsed permission request
     * @param checker  the PermissionChecker (may be null for simple auto-approve)
     * @return the resolution ("approved" or "denied")
     */
    public static String handlePermissionRequest(SwarmPermissionRequest request,
                                                  com.openharness.permissions.PermissionChecker checker) {
        // Auto-approve read-only tools
        if (isReadOnly(request.toolName)) {
            logger.debug("Auto-approved read-only tool: {} by {}",
                    request.toolName, request.workerId);
            return "approved";
        }

        // Delegate to permission checker if available
        if (checker != null) {
            String filePath = null;
            String command = null;
            Map<String, Object> args = request.arguments;
            if (args != null) {
                if (args.containsKey("file_path") || args.containsKey("path")) {
                    Object fp = args.getOrDefault("file_path", args.get("path"));
                    filePath = fp != null ? fp.toString() : null;
                }
                if (args.containsKey("command")) {
                    command = args.get("command").toString();
                }
            }
            var decision = checker.evaluate(request.toolName,
                    isReadOnly(request.toolName), filePath, command);
            if (decision.allowed()) return "approved";
            if (decision.requiresConfirmation()) return "pending_confirmation";
            return "denied";
        }

        // Default: deny mutating tools when no checker is available
        return "denied";
    }
}
