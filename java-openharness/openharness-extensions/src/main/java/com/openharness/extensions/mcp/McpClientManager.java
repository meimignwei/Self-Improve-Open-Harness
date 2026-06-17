package com.openharness.extensions.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.McpClient;
import com.openharness.common.McpToolInfo;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Manages MCP server connections, tool dispatch, and resource access.
 * Java equivalent of Python's McpClientManager.
 *
 * Supports stdio, HTTP+SSE, WebSocket, and Streamable HTTP transports.
 */
public class McpClientManager implements com.openharness.common.McpClient {

    private static final Logger LOG = Logger.getLogger(McpClientManager.class.getName());

    private final Map<String, McpConnectionState> connections = new ConcurrentHashMap<>();
    private final Map<String, McpTransport> transports = new ConcurrentHashMap<>();
    private final Map<String, McpServerConfig> serverConfigs = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public McpClientManager() {
        this.mapper = OpenHarnessObjectMapper.get();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Connect all configured MCP servers.
     */
    public void connectAll(List<McpServerConfig> configs) {
        for (McpServerConfig config : configs) {
            serverConfigs.put(config.name(), config);
            try {
                McpConnectionState state = switch (config) {
                    case McpServerConfig.StdioConfig sc -> connectStdio(sc);
                    case McpServerConfig.HttpConfig hc -> connectHttp(hc);
                    case McpServerConfig.WebSocketConfig wc -> connectWs(wc);
                    case McpServerConfig.StreamableHttpConfig sc -> connectStreamableHttp(sc);
                };
                connections.put(state.name(), state);
                LOG.info("MCP connected: " + state.name() + " (" + state.transport() + ")");
            } catch (Exception e) {
                LOG.warning("MCP connect failed for " + config.name() + ": " + e.getMessage());
                connections.put(config.name(), McpConnectionState.failed(config.name(), e));
            }
        }
    }

    // ── Stdio transport ──────────────────────────────────────────────

    private McpConnectionState connectStdio(McpServerConfig.StdioConfig config) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.command());
        if (config.args() != null) cmd.addAll(config.args());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (config.cwd() != null) pb.directory(Path.of(config.cwd()).toFile());
        if (config.env() != null) pb.environment().putAll(config.env());

        Process process = pb.start();
        StdioTransport transport = new StdioTransport(process, mapper);

        // Initialize handshake
        JsonNode initResult = transport.sendRequest(
                buildRequest("initialize", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of())));

        // MCP spec: send notifications/initialized after initialize succeeds
        if (!hasError(initResult)) {
            transport.sendNotification(buildNotification("notifications/initialized", Map.of()));
        }

        // List tools
        JsonNode toolsResult = transport.sendRequest(
                buildRequest("tools/list", Map.of()));
        List<McpToolInfo> tools = parseToolList(toolsResult, config.name());

        // List resources
        JsonNode resourcesResult = transport.sendRequest(
                buildRequest("resources/list", Map.of()));
        List<McpClient.McpResourceInfo> resources = parseResourceList(resourcesResult, config.name());

        transports.put(config.name(), transport);

        return new McpConnectionState(config.name(), ConnectionState.CONNECTED,
                "Connected via stdio", "stdio", false, tools, resources);
    }

    // ── HTTP transport ───────────────────────────────────────────────

    private McpConnectionState connectHttp(McpServerConfig.HttpConfig config) throws IOException {
        HttpTransport transport = new HttpTransport(config.url(), config.headers(),
                config.auth(), httpClient, mapper);

        // Initialize handshake
        JsonNode initResult = transport.sendRequest(
                buildRequest("initialize", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of())));

        // MCP spec: send notifications/initialized after initialize succeeds
        if (!hasError(initResult)) {
            transport.sendNotification(buildNotification("notifications/initialized", Map.of()));
        }

        // List tools
        JsonNode toolsResult = transport.sendRequest(
                buildRequest("tools/list", Map.of()));
        List<McpToolInfo> tools = parseToolList(toolsResult, config.name());

        // List resources
        JsonNode resourcesResult = transport.sendRequest(
                buildRequest("resources/list", Map.of()));
        List<McpClient.McpResourceInfo> resources = parseResourceList(resourcesResult, config.name());

        transports.put(config.name(), transport);

        boolean hasAuth = config.auth() != null && !config.auth().isEmpty();
        return new McpConnectionState(config.name(), ConnectionState.CONNECTED,
                "Connected via HTTP", "http", hasAuth, tools, resources);
    }

    // ── WebSocket transport ──────────────────────────────────────────

    private McpConnectionState connectWs(McpServerConfig.WebSocketConfig config) throws IOException {
        WebSocketTransport transport = new WebSocketTransport(config.url(), config.headers(),
                config.auth(), httpClient, mapper);

        // Initialize handshake
        JsonNode initResult = transport.sendRequest(
                buildRequest("initialize", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of())));

        // MCP spec: send notifications/initialized after initialize succeeds
        if (!hasError(initResult)) {
            transport.sendNotification(buildNotification("notifications/initialized", Map.of()));
        }

        // List tools
        JsonNode toolsResult = transport.sendRequest(
                buildRequest("tools/list", Map.of()));
        List<McpToolInfo> tools = parseToolList(toolsResult, config.name());

        // List resources
        JsonNode resourcesResult = transport.sendRequest(
                buildRequest("resources/list", Map.of()));
        List<McpClient.McpResourceInfo> resources = parseResourceList(resourcesResult, config.name());

        transports.put(config.name(), transport);

        boolean hasAuth = config.auth() != null && !config.auth().isEmpty();
        return new McpConnectionState(config.name(), ConnectionState.CONNECTED,
                "Connected via WebSocket", "ws", hasAuth, tools, resources);
    }

    // ── Streamable HTTP transport ────────────────────────────────────

    private McpConnectionState connectStreamableHttp(McpServerConfig.StreamableHttpConfig config) throws IOException {
        StreamableHttpTransport transport = new StreamableHttpTransport(config.url(), config.headers(),
                config.auth(), httpClient, mapper);

        // Initialize handshake
        JsonNode initResult = transport.sendRequest(
                buildRequest("initialize", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of())));

        // MCP spec: send notifications/initialized after initialize succeeds
        if (!hasError(initResult)) {
            transport.sendNotification(buildNotification("notifications/initialized", Map.of()));
        }

        // List tools
        JsonNode toolsResult = transport.sendRequest(
                buildRequest("tools/list", Map.of()));
        List<McpToolInfo> tools = parseToolList(toolsResult, config.name());

        // List resources
        JsonNode resourcesResult = transport.sendRequest(
                buildRequest("resources/list", Map.of()));
        List<McpClient.McpResourceInfo> resources = parseResourceList(resourcesResult, config.name());

        transports.put(config.name(), transport);

        boolean hasAuth = config.auth() != null && !config.auth().isEmpty();
        return new McpConnectionState(config.name(), ConnectionState.CONNECTED,
                "Connected via Streamable HTTP", "streamable-http", hasAuth, tools, resources);
    }

    // ── Tool & resource dispatch ─────────────────────────────────────

    @Override
    public String callTool(String serverName, String toolName, JsonNode args) {
        McpConnectionState conn = connections.get(serverName);
        if (conn == null || conn.state() != ConnectionState.CONNECTED) {
            return "Error: MCP server '" + serverName + "' is not connected";
        }

        McpTransport transport = transports.get(serverName);
        if (transport == null) {
            return "Error: MCP transport not available for '" + serverName + "'";
        }

        try {
            JsonNode result = transport.sendRequest(buildRequest("tools/call", Map.of(
                    "name", toolName,
                    "arguments", args != null ? args : mapper.createObjectNode())));
            return formatToolResult(result);
        } catch (Exception e) {
            LOG.warning("MCP tool call failed: " + e.getMessage());
            return "Error: MCP tool call failed: " + e.getMessage();
        }
    }

    public List<McpToolInfo> listTools() {
        return connections.values().stream()
                .filter(c -> c.state() == ConnectionState.CONNECTED)
                .flatMap(c -> c.tools().stream())
                .toList();
    }

    @Override
    public List<McpClient.McpResourceInfo> listResources(String serverName) {
        McpConnectionState conn = connections.get(serverName);
        if (conn == null || conn.state() != ConnectionState.CONNECTED) {
            return List.of();
        }
        return conn.resources();
    }

    /**
     * List MCP resources from all connected servers (matching Python's list_resources).
     */
    @Override
    public List<McpClient.McpResourceInfo> listResources() {
        return connections.values().stream()
                .filter(c -> c.state() == ConnectionState.CONNECTED)
                .flatMap(c -> c.resources().stream())
                .toList();
    }

    @Override
    public String readResource(String serverName, String uri) {
        McpConnectionState conn = connections.get(serverName);
        if (conn == null || conn.state() != ConnectionState.CONNECTED) {
            return "Error: MCP server '" + serverName + "' is not connected";
        }

        McpTransport transport = transports.get(serverName);
        if (transport == null) {
            return "Error: MCP transport not available for '" + serverName + "'";
        }

        try {
            JsonNode result = transport.sendRequest(buildRequest("resources/read", Map.of("uri", uri)));
            return formatResourceResult(result);
        } catch (Exception e) {
            LOG.warning("MCP resource read failed: " + e.getMessage());
            return "Error: MCP resource read failed: " + e.getMessage();
        }
    }

    // ── MCP Prompts ──────────────────────────────────────────────────

    /**
     * List available prompts from an MCP server.
     */
    @Override
    public List<McpClient.McpPromptInfo> listPrompts(String serverName) {
        McpConnectionState conn = connections.get(serverName);
        if (conn == null || conn.state() != ConnectionState.CONNECTED) {
            return List.of();
        }
        McpTransport transport = transports.get(serverName);
        if (transport == null) return List.of();

        try {
            JsonNode result = transport.sendRequest(buildRequest("prompts/list", Map.of()));
            return parsePromptList(result, serverName);
        } catch (Exception e) {
            LOG.warning("MCP prompts/list failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Get a prompt with resolved arguments from an MCP server.
     */
    @Override
    public String getPrompt(String serverName, String promptName, Map<String, String> arguments) {
        McpConnectionState conn = connections.get(serverName);
        if (conn == null || conn.state() != ConnectionState.CONNECTED) {
            return "Error: MCP server '" + serverName + "' is not connected";
        }
        McpTransport transport = transports.get(serverName);
        if (transport == null) {
            return "Error: MCP transport not available for '" + serverName + "'";
        }

        try {
            JsonNode result = transport.sendRequest(buildRequest("prompts/get", Map.of(
                    "name", promptName,
                    "arguments", arguments != null ? arguments : Map.of())));
            return formatPromptResult(result);
        } catch (Exception e) {
            LOG.warning("MCP prompts/get failed: " + e.getMessage());
            return "Error: MCP prompts/get failed: " + e.getMessage();
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    public List<McpConnectionState> listStatuses() {
        return List.copyOf(connections.values());
    }

    @Override
    public void disconnect(String serverName) {
        McpTransport transport = transports.remove(serverName);
        if (transport != null) {
            transport.close();
        }
        connections.remove(serverName);
    }

    /**
     * Disconnect and reconnect all configured servers.
     */
    public void reconnectAll() {
        closeAll();
        connectAll(List.copyOf(serverConfigs.values()));
    }

    /**
     * Update a server's config in memory. Call {@link #reconnectAll()} to apply.
     */
    public void updateServerConfig(String name, McpServerConfig config) {
        serverConfigs.put(name, config);
    }

    /**
     * Close all connections and clean up transports.
     */
    public void closeAll() {
        for (String name : List.copyOf(transports.keySet())) {
            disconnect(name);
        }
    }

    /**
     * Get a copy of the currently stored server configs.
     */
    public Map<String, McpServerConfig> getServerConfigs() {
        return Map.copyOf(serverConfigs);
    }

    /**
     * Get a specific server config by name (matching Python's get_server_config).
     */
    public McpServerConfig getServerConfig(String name) {
        return serverConfigs.get(name);
    }

    // ── JSON-RPC helpers ─────────────────────────────────────────────

    private JsonNode buildRequest(String method, Map<String, Object> params) {
        var node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", UUID.randomUUID().toString());
        node.put("method", method);
        node.set("params", mapper.valueToTree(params));
        return node;
    }

    /** Build a JSON-RPC notification (no id field). */
    private JsonNode buildNotification(String method, Map<String, Object> params) {
        var node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        node.set("params", mapper.valueToTree(params));
        return node;
    }

    private static boolean hasError(JsonNode result) {
        return result != null && result.has("error");
    }

    // ── Response parsing ─────────────────────────────────────────────

    private List<McpToolInfo> parseToolList(JsonNode result, String serverName) {
        List<McpToolInfo> tools = new ArrayList<>();
        JsonNode toolList = result.get("result");
        if (toolList != null && toolList.has("tools")) {
            for (JsonNode t : toolList.get("tools")) {
                tools.add(new McpToolInfo(
                        serverName,
                        t.get("name").asText(),
                        t.has("description") ? t.get("description").asText() : "",
                        t.get("inputSchema")));
            }
        }
        return tools;
    }

    private List<McpClient.McpResourceInfo> parseResourceList(JsonNode result, String serverName) {
        List<McpClient.McpResourceInfo> resources = new ArrayList<>();
        JsonNode resourceList = result.get("result");
        if (resourceList != null && resourceList.has("resources")) {
            for (JsonNode r : resourceList.get("resources")) {
                resources.add(new McpClient.McpResourceInfo(
                        serverName,
                        r.has("name") ? r.get("name").asText() : "",
                        r.has("uri") ? r.get("uri").asText() : "",
                        r.has("description") ? r.get("description").asText() : ""));
            }
        }
        return resources;
    }

    private List<McpClient.McpPromptInfo> parsePromptList(JsonNode result, String serverName) {
        List<McpClient.McpPromptInfo> prompts = new ArrayList<>();
        JsonNode promptList = result.get("result");
        if (promptList != null && promptList.has("prompts")) {
            for (JsonNode p : promptList.get("prompts")) {
                List<McpClient.McpPromptArgInfo> args = new ArrayList<>();
                if (p.has("arguments")) {
                    for (JsonNode a : p.get("arguments")) {
                        args.add(new McpClient.McpPromptArgInfo(
                                a.get("name").asText(),
                                a.has("description") ? a.get("description").asText() : "",
                                a.has("required") && a.get("required").asBoolean()));
                    }
                }
                prompts.add(new McpClient.McpPromptInfo(
                        serverName,
                        p.get("name").asText(),
                        p.has("description") ? p.get("description").asText() : "",
                        args));
            }
        }
        return prompts;
    }

    private String formatToolResult(JsonNode result) {
        if (result == null) {
            return "Error: Empty response from MCP server";
        }
        if (result.has("error")) {
            return "MCP Error: " + result.get("error");
        }
        JsonNode content = result.get("result");
        if (content == null) {
            return result.toString();
        }
        if (content.has("content") && content.get("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content.get("content")) {
                if (item.has("text")) {
                    sb.append(item.get("text").asText());
                } else {
                    sb.append(item.toString());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private String formatResourceResult(JsonNode result) {
        if (result == null) {
            return "Error: Empty response from MCP server";
        }
        if (result.has("error")) {
            return "MCP Error: " + result.get("error");
        }
        JsonNode content = result.get("result");
        if (content == null) {
            return result.toString();
        }
        if (content.has("contents") && content.get("contents").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content.get("contents")) {
                if (item.has("text")) {
                    sb.append(item.get("text").asText());
                } else {
                    sb.append(item.toString());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private String formatPromptResult(JsonNode result) {
        if (result == null) {
            return "Error: Empty response from MCP server";
        }
        if (result.has("error")) {
            return "MCP Error: " + result.get("error");
        }
        JsonNode content = result.get("result");
        if (content == null) {
            return result.toString();
        }
        if (content.has("messages") && content.get("messages").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode msg : content.get("messages")) {
                JsonNode role = msg.get("role");
                if (role != null) sb.append("[").append(role.asText()).append("]: ");
                JsonNode ct = msg.get("content");
                if (ct != null) {
                    if (ct.isTextual()) {
                        sb.append(ct.asText());
                    } else if (ct.has("text")) {
                        sb.append(ct.get("text").asText());
                    } else {
                        sb.append(ct.toString());
                    }
                }
                sb.append("\n");
            }
            return sb.toString();
        }
        return content.toString();
    }

    // ══════════════════════════════════════════════════════════════════
    // Transport implementations
    // ══════════════════════════════════════════════════════════════════

    /**
     * Internal transport abstraction for MCP communication.
     */
    private interface McpTransport {
        JsonNode sendRequest(JsonNode request) throws IOException;
        default void sendNotification(JsonNode notification) throws IOException {
            // Default: no-op for transports that don't support notifications separately
        }
        void close();
    }

    // ── Stdio Transport ──────────────────────────────────────────────

    private static class StdioTransport implements McpTransport {
        private final Process process;
        private final BufferedReader reader;
        private final ObjectMapper mapper;

        StdioTransport(Process process, ObjectMapper mapper) {
            this.process = process;
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            this.mapper = mapper;
        }

        @Override
        public synchronized JsonNode sendRequest(JsonNode request) throws IOException {
            String json = mapper.writeValueAsString(request) + "\n";
            process.getOutputStream().write(json.getBytes());
            process.getOutputStream().flush();

            String line = reader.readLine();
            if (line != null) {
                return mapper.readTree(line);
            }
            return mapper.createObjectNode();
        }

        @Override
        public void sendNotification(JsonNode notification) throws IOException {
            String json = mapper.writeValueAsString(notification) + "\n";
            process.getOutputStream().write(json.getBytes());
            process.getOutputStream().flush();
            // Notifications don't receive a response
        }

        @Override
        public void close() {
            process.destroy();
            try {
                reader.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    // ── HTTP Transport ───────────────────────────────────────────────

    /**
     * HTTP-based JSON-RPC transport using POST requests.
     * Supports Bearer token and custom header authentication.
     */
    private static class HttpTransport implements McpTransport {
        private final String url;
        private final Map<String, String> headers;
        private final Map<String, Object> auth;
        private final HttpClient httpClient;
        private final ObjectMapper mapper;

        HttpTransport(String url, Map<String, String> headers, Map<String, Object> auth,
                      HttpClient httpClient, ObjectMapper mapper) {
            this.url = url;
            this.headers = headers != null ? headers : Map.of();
            this.auth = auth != null ? auth : Map.of();
            this.httpClient = httpClient;
            this.mapper = mapper;
        }

        @Override
        public JsonNode sendRequest(JsonNode request) throws IOException {
            try {
                String body = mapper.writeValueAsString(request);

                var builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30));

                applyHeaders(builder);

                HttpRequest httpRequest = builder.build();
                HttpResponse<String> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());

                if (response.body() == null || response.body().isBlank()) {
                    return mapper.createObjectNode();
                }
                return mapper.readTree(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("HTTP request interrupted", e);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("HTTP transport error: " + e.getMessage(), e);
            }
        }

        @Override
        public void sendNotification(JsonNode notification) throws IOException {
            try {
                String body = mapper.writeValueAsString(notification);

                var builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10));

                applyHeaders(builder);

                HttpRequest httpRequest = builder.build();
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.fine("MCP notification failed: " + e.getMessage());
            }
        }

        private void applyHeaders(HttpRequest.Builder builder) {
            // Apply configured headers
            for (var entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }

            // Apply auth
            String authType = (String) auth.getOrDefault("type", "");
            switch (authType) {
                case "bearer" -> {
                    String token = (String) auth.get("token");
                    if (token != null && !token.isBlank()) {
                        builder.header("Authorization", "Bearer " + token);
                    }
                }
                case "header" -> {
                    String key = (String) auth.get("key");
                    String value = (String) auth.get("value");
                    if (key != null && !key.isBlank() && value != null) {
                        builder.header(key, value);
                    }
                }
                case "env" -> {
                    String key = (String) auth.get("key");
                    String value = (String) auth.get("value");
                    if (key != null && value != null) {
                        String envValue = System.getenv(value);
                        if (envValue != null && !envValue.isBlank()) {
                            builder.header(key, envValue);
                        }
                    }
                }
            }
        }

        @Override
        public void close() {
            // HTTP transport is stateless — no persistent connection to close
        }
    }

    // ── WebSocket Transport ──────────────────────────────────────────

    /**
     * WebSocket-based JSON-RPC transport.
     * Uses java.net.http.WebSocket for persistent bidirectional communication.
     */
    private static class WebSocketTransport implements McpTransport {
        private final WebSocket webSocket;
        private final ObjectMapper mapper;
        private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
        private volatile Exception connectError;

        WebSocketTransport(String url, Map<String, String> headers, Map<String, Object> auth,
                           HttpClient httpClient, ObjectMapper mapper) throws IOException {
            this.mapper = mapper;

            // Build the WebSocket builder with auth headers
            var wsBuilder = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10));

            // Apply auth headers
            if (headers != null) {
                for (var entry : headers.entrySet()) {
                    wsBuilder.header(entry.getKey(), entry.getValue());
                }
            }
            if (auth != null && !auth.isEmpty()) {
                applyAuthHeaders(wsBuilder, auth);
            }

            try {
                this.webSocket = wsBuilder
                        .buildAsync(URI.create(url), new WsListener())
                        .get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException("WebSocket connection failed: " + e.getMessage(), e);
            }

            if (connectError != null) {
                throw new IOException("WebSocket connection error", connectError);
            }
        }

        @SuppressWarnings("unchecked")
        private static void applyAuthHeaders(WebSocket.Builder builder, Map<String, Object> auth) {
            String authType = (String) auth.getOrDefault("type", "");
            switch (authType) {
                case "bearer" -> {
                    String token = (String) auth.get("token");
                    if (token != null && !token.isBlank()) {
                        builder.header("Authorization", "Bearer " + token);
                    }
                }
                case "header" -> {
                    String key = (String) auth.get("key");
                    String value = (String) auth.get("value");
                    if (key != null && !key.isBlank() && value != null) {
                        builder.header(key, value);
                    }
                }
                case "env" -> {
                    String key = (String) auth.get("key");
                    String value = (String) auth.get("value");
                    if (key != null && value != null) {
                        String envValue = System.getenv(value);
                        if (envValue != null && !envValue.isBlank()) {
                            builder.header(key, envValue);
                        }
                    }
                }
            }
        }

        @Override
        public JsonNode sendRequest(JsonNode request) throws IOException {
            String id = request.has("id") ? request.get("id").asText() : UUID.randomUUID().toString();
            if (!request.has("id")) {
                ((ObjectNode) request).put("id", id);
            }

            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pendingRequests.put(id, future);

            try {
                String json = mapper.writeValueAsString(request);
                webSocket.sendText(json, true).get(30, TimeUnit.SECONDS);
                return future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                pendingRequests.remove(id);
                throw new IOException("WebSocket request failed: " + e.getMessage(), e);
            }
        }

        @Override
        public void sendNotification(JsonNode notification) throws IOException {
            try {
                String json = mapper.writeValueAsString(notification);
                webSocket.sendText(json, true).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.fine("WebSocket notification failed: " + e.getMessage());
            }
        }

        @Override
        public void close() {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect").get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // abort if graceful close fails
                webSocket.abort();
            }
            pendingRequests.values().forEach(f -> f.completeExceptionally(
                    new IOException("WebSocket disconnected")));
            pendingRequests.clear();
        }

        private class WsListener implements WebSocket.Listener {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void onOpen(WebSocket ws) {
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    try {
                        JsonNode node = mapper.readTree(message);
                        if (node.has("id")) {
                            String id = node.get("id").asText();
                            CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                            if (future != null) {
                                future.complete(node);
                            }
                        }
                    } catch (Exception e) {
                        LOG.fine("WebSocket: failed to parse message: " + e.getMessage());
                    }
                }
                ws.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                connectError = error instanceof Exception ? (Exception) error
                        : new Exception(error);
                pendingRequests.values().forEach(f -> f.completeExceptionally(error));
                pendingRequests.clear();
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                pendingRequests.values().forEach(f -> f.completeExceptionally(
                        new IOException("WebSocket closed: " + reason)));
                pendingRequests.clear();
                return null;
            }
        }
    }

    // ── Streamable HTTP Transport ────────────────────────────────────

    /**
     * Streamable HTTP transport (MCP spec 2025+).
     * Sends JSON-RPC requests via HTTP POST with streaming response support.
     * Supports Accept: text/event-stream for SSE-style server push.
     */
    private static class StreamableHttpTransport implements McpTransport {
        private final String url;
        private final Map<String, String> headers;
        private final Map<String, Object> auth;
        private final HttpClient httpClient;
        private final ObjectMapper mapper;

        StreamableHttpTransport(String url, Map<String, String> headers, Map<String, Object> auth,
                                HttpClient httpClient, ObjectMapper mapper) {
            this.url = url;
            this.headers = headers != null ? headers : Map.of();
            this.auth = auth != null ? auth : Map.of();
            this.httpClient = httpClient;
            this.mapper = mapper;
        }

        @Override
        public JsonNode sendRequest(JsonNode request) throws IOException {
            try {
                String body = mapper.writeValueAsString(request);

                var builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .timeout(Duration.ofSeconds(30));

                applyHeaders(builder);

                HttpRequest httpRequest = builder.build();
                HttpResponse<String> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());

                if (response.body() == null || response.body().isBlank()) {
                    return mapper.createObjectNode();
                }

                // Handle potential SSE-style response
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (contentType.contains("text/event-stream")) {
                    return parseSseResponse(response.body());
                }
                return mapper.readTree(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Streamable HTTP request interrupted", e);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Streamable HTTP transport error: " + e.getMessage(), e);
            }
        }

        @Override
        public void sendNotification(JsonNode notification) throws IOException {
            try {
                String body = mapper.writeValueAsString(notification);

                var builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10));

                applyHeaders(builder);

                HttpRequest httpRequest = builder.build();
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.fine("Streamable HTTP notification failed: " + e.getMessage());
            }
        }

        private void applyHeaders(HttpRequest.Builder builder) {
            for (var entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            String authType = (String) auth.getOrDefault("type", "");
            switch (authType) {
                case "bearer" -> {
                    String token = (String) auth.get("token");
                    if (token != null && !token.isBlank()) {
                        builder.header("Authorization", "Bearer " + token);
                    }
                }
                case "header" -> {
                    String key = (String) auth.get("key");
                    String value = (String) auth.get("value");
                    if (key != null && !key.isBlank() && value != null) {
                        builder.header(key, value);
                    }
                }
                case "env" -> {
                    String key = (String) auth.get("key");
                    String value = (String) auth.get("value");
                    if (key != null && value != null) {
                        String envValue = System.getenv(value);
                        if (envValue != null && !envValue.isBlank()) {
                            builder.header(key, envValue);
                        }
                    }
                }
            }
        }

        /** Parse SSE format: "data: <json>\n\n" */
        private JsonNode parseSseResponse(String body) throws Exception {
            for (String line : body.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    String data = trimmed.substring(5).trim();
                    if (!data.isEmpty()) {
                        return mapper.readTree(data);
                    }
                }
            }
            return mapper.createObjectNode();
        }

        @Override
        public void close() {
            // Stateless — no persistent connection
        }
    }
}
