package com.openharness.extensions.sandbox;

import com.openharness.config.SandboxSettings;
import com.openharness.extensions.swarm.FileMailbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Unified sandbox orchestrator.
 *
 * Detects available backends (SRT), manages config lifecycle,
 * wraps commands, and integrates with the permission system.
 *
 * Java equivalent of Python sandbox/manager.py.
 */
public class SandboxManager {

    private static final Logger logger = LoggerFactory.getLogger(SandboxManager.class);

    private final SrtSandbox srt;
    private final SandboxSettings defaultSettings;
    private final Map<String, SandboxSettings> perToolSettings = new ConcurrentHashMap<>();

    // Pending sandbox permission requests (host -> request data)
    private final Map<String, SandboxPermissionRequest> pendingNetworkRequests = new ConcurrentHashMap<>();

    // Callback for when sandbox needs permission (e.g., network access)
    private Consumer<SandboxPermissionRequest> permissionRequestHandler;

    public SandboxManager(SandboxSettings defaultSettings) {
        this.defaultSettings = defaultSettings != null ? defaultSettings : new SandboxSettings();
        this.srt = new SrtSandbox();
    }

    public SandboxManager() {
        this(new SandboxSettings());
    }

    public SrtSandbox srt() {
        return srt;
    }

    // ------------------------------------------------------------------
    // Backend detection
    // ------------------------------------------------------------------

    public boolean isAvailable() {
        return srt.isAvailable();
    }

    public String activeBackend() {
        return srt.isAvailable() ? "srt" : "none";
    }

    public String sandboxEngine() {
        return srt.sandboxEngine();
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("srt_available", srt.isAvailable());
        s.put("engine", srt.sandboxEngine());
        s.put("default_settings", Map.of(
                "enabled", defaultSettings.enabled(),
                "backend", defaultSettings.backend()));
        return s;
    }

    // ------------------------------------------------------------------
    // Permission request handler
    // ------------------------------------------------------------------

    public void setPermissionRequestHandler(Consumer<SandboxPermissionRequest> handler) {
        this.permissionRequestHandler = handler;
    }

    /**
     * Resolve a pending network permission request.
     */
    public void resolveNetworkPermission(String requestId, String host, boolean allow) {
        SandboxPermissionRequest req = pendingNetworkRequests.remove(requestId);
        if (req != null) {
            logger.info("Sandbox network permission {}: {} -> {}", allow ? "approved" : "denied", requestId, host);
            if (allow) {
                SandboxSettings.SandboxNetworkSettings net = defaultSettings.network();
                if (!net.allowedDomains().contains(host)) {
                    net.allowedDomains().add(host);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-tool sandbox settings
    // ------------------------------------------------------------------

    public void configureTool(String toolName, SandboxSettings settings) {
        perToolSettings.put(toolName, settings);
    }

    public SandboxSettings settingsFor(String toolName) {
        return perToolSettings.getOrDefault(toolName, defaultSettings);
    }

    // ------------------------------------------------------------------
    // Command execution
    // ------------------------------------------------------------------

    /**
     * Execute a command in the sandbox. Falls back to direct execution
     * if SRT is unavailable and failIfUnavailable is false.
     *
     * @param command     the shell command
     * @param toolName    tool executing the command (for per-tool settings)
     * @param workingDir  working directory
     * @return execution result
     */
    public SandboxExecutionResult execute(String command, String toolName, Path workingDir) {
        SandboxSettings settings = settingsFor(toolName);

        if (!settings.enabled()) {
            return new SandboxExecutionResult(false, false,
                    null, "Sandbox disabled in settings");
        }

        if (!SrtSandbox.isSandboxable(command)) {
            return new SandboxExecutionResult(false, false,
                    null, "Command not sandboxable: " + command);
        }

        if (!srt.isAvailable()) {
            if (settings.failIfUnavailable()) {
                return new SandboxExecutionResult(false, true,
                        null, "SRT not available and failIfUnavailable is set");
            }
            logger.warn("SRT not available, executing without sandbox: {}", command);
            return new SandboxExecutionResult(false, false,
                    null, "SRT not available — sandbox bypassed");
        }

        try {
            // Check if network access needs permission
            if (needsNetworkPermission(command, settings)) {
                SandboxPermissionRequest req = new SandboxPermissionRequest(
                        UUID.randomUUID().toString(),
                        extractHost(command),
                        command, toolName);
                pendingNetworkRequests.put(req.requestId, req);

                if (permissionRequestHandler != null) {
                    permissionRequestHandler.accept(req);
                }

                // If still pending after handler, return pending status
                if (pendingNetworkRequests.containsKey(req.requestId)) {
                    return new SandboxExecutionResult(true, false,
                            req, "Awaiting network permission");
                }
            }

            SrtSandbox.SrtResult result = srt.execute(command, settings, workingDir);
            return new SandboxExecutionResult(true, true,
                    null, null, result);

        } catch (IOException e) {
            logger.error("Sandbox execution failed: {}", command, e);
            return new SandboxExecutionResult(true, true,
                    null, "Sandbox execution error: " + e.getMessage());
        }
    }

    /**
     * Execute with default tool settings.
     */
    public SandboxExecutionResult execute(String command, Path workingDir) {
        return execute(command, "default", workingDir);
    }

    // ------------------------------------------------------------------
    // Message creation (for FileMailbox sandbox messages)
    // ------------------------------------------------------------------

    /**
     * Create a sandbox_permission_request mailbox message for network access.
     */
    public FileMailbox.MailboxMessage createNetworkPermissionMessage(
            String senderAgentId, String leaderAgentId,
            String requestId, String host, String workerName) {

        Map<String, Object> requestData = new LinkedHashMap<>();
        requestData.put("requestId", requestId);
        requestData.put("workerId", senderAgentId);
        requestData.put("workerName", workerName != null ? workerName : senderAgentId);
        requestData.put("host", host);
        requestData.put("createdAt", System.currentTimeMillis());

        return FileMailbox.createSandboxPermissionRequestMessage(
                senderAgentId, leaderAgentId, requestData);
    }

    /**
     * Create a sandbox_permission_response mailbox message.
     */
    public FileMailbox.MailboxMessage createNetworkPermissionResponse(
            String leaderAgentId, String workerAgentId,
            String requestId, String host, boolean allow) {

        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("requestId", requestId);
        responseData.put("host", host);
        responseData.put("allow", allow);

        return FileMailbox.createSandboxPermissionResponseMessage(
                leaderAgentId, workerAgentId, responseData);
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private boolean needsNetworkPermission(String command, SandboxSettings settings) {
        // If network is already allowed in settings, no need to ask
        SandboxSettings.SandboxNetworkSettings net = settings.network();
        if (net.allowedDomains() != null && net.allowedDomains().contains("*")) {
            return false;
        }
        // Check if command tries to access the network
        String lower = command.toLowerCase();
        return lower.contains("curl ") || lower.contains("wget ")
                || lower.contains("http://") || lower.contains("https://")
                || lower.contains("pip install") || lower.contains("npm install")
                || lower.contains("git clone") || lower.contains("git fetch")
                || lower.contains("git pull") || lower.contains("api.");
    }

    private String extractHost(String command) {
        // Try to extract a hostname from the command
        for (String prefix : List.of("https://", "http://")) {
            int idx = command.indexOf(prefix);
            if (idx >= 0) {
                String rest = command.substring(idx + prefix.length());
                int end = rest.indexOf('/');
                if (end < 0) end = rest.indexOf(' ');
                if (end < 0) end = rest.indexOf('"');
                if (end < 0) end = rest.indexOf('\'');
                if (end < 0) end = rest.length();
                return rest.substring(0, end);
            }
        }
        return "unknown";
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    public record SandboxPermissionRequest(
            String requestId,
            String host,
            String command,
            String toolName
    ) {}

    public record SandboxExecutionResult(
            boolean sandboxEnabled,
            boolean sandboxApplied,
            SandboxPermissionRequest pendingPermission,
            String message,
            SrtSandbox.SrtResult srtResult
    ) {
        public SandboxExecutionResult(boolean enabled, boolean applied,
                                       SandboxPermissionRequest pending, String msg) {
            this(enabled, applied, pending, msg, null);
        }

        public boolean success() {
            return srtResult != null && srtResult.success();
        }

        public String output() {
            if (srtResult != null) return srtResult.stdout();
            if (message != null) return message;
            return "";
        }
    }
}
