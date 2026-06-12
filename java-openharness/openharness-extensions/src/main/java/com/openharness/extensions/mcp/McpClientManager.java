package com.openharness.extensions.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.McpClient;
import com.openharness.common.McpToolInfo;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages MCP server connections, tool dispatch, and resource access.
 * Java equivalent of Python's McpClientManager.
 */
public class McpClientManager implements com.openharness.common.McpClient {

    private static final Logger LOG = Logger.getLogger(McpClientManager.class.getName());

    private final Map<String, McpConnectionState> connections = new ConcurrentHashMap<>();
    private final Map<String, McpTransport> transports = new ConcurrentHashMap<>();
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
            try {
                McpConnectionState state = switch (config) {
                    case McpServerConfig.StdioConfig sc -> connectStdio(sc);
                    case McpServerConfig.HttpConfig hc -> connectHttp(hc);
                    case McpServerConfig.WebSocketConfig wc -> connectWs(wc);
                };
                connections.put(state.name(), state);
                LOG.info("MCP connected: " + state.name() + " (" + state.transport() + ")");
            } catch (Exception e) {
                LOG.warning("MCP connect failed for " + config.name() + ": " + e.getMessage());
                connections.put(config.name(), McpConnectionState.failed(config.name(), e));
            }
        }
    }

    private McpConnectionState connectStdio(McpServerConfig.StdioConfig config) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.command());
        if (config.args() != null) cmd.addAll(config.args());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (config.cwd() != null) pb.directory(Path.of(config.cwd()).toFile());
        if (config.env() != null) pb.environment().putAll(config.env());

        Process process = pb.start();
        StdioTransport transport = new StdioTransport(process, mapper);

        // Send initialize request and read response
        JsonNode initResult = transport.sendRequest(
                buildRequest("initialize", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of())));

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

    private McpConnectionState connectHttp(McpServerConfig.HttpConfig config) {
        return new McpConnectionState(config.name(), ConnectionState.CONNECTED,
                "Connected via HTTP", "http", true, List.of(), List.of());
    }

    private McpConnectionState connectWs(McpServerConfig.WebSocketConfig config) {
        return new McpConnectionState(config.name(), ConnectionState.CONNECTED,
                "Connected via WebSocket", "ws", true, List.of(), List.of());
    }

    /**
     * Call an MCP tool by name.
     */
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
    public List<com.openharness.common.McpClient.McpResourceInfo> listResources(String serverName) {
        McpConnectionState conn = connections.get(serverName);
        if (conn == null || conn.state() != ConnectionState.CONNECTED) {
            return List.of();
        }
        return conn.resources();
    }

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
            JsonNode result = transport.sendRequest(buildRequest("resources/read", Map.of(
                    "uri", uri)));
            return formatResourceResult(result);
        } catch (Exception e) {
            LOG.warning("MCP resource read failed: " + e.getMessage());
            return "Error: MCP resource read failed: " + e.getMessage();
        }
    }

    public List<McpConnectionState> listStatuses() {
        return List.copyOf(connections.values());
    }

    public void disconnect(String serverName) {
        McpTransport transport = transports.remove(serverName);
        if (transport != null) {
            transport.close();
        }
        connections.remove(serverName);
    }

    private JsonNode buildRequest(String method, Map<String, Object> params) {
        var node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", UUID.randomUUID().toString());
        node.put("method", method);
        node.set("params", mapper.valueToTree(params));
        return node;
    }

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
        // MCP tools/call result has content array
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
        // resources/read returns contents array with text/blob
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

    /**
     * Internal transport abstraction for MCP communication.
     */
    private interface McpTransport {
        JsonNode sendRequest(JsonNode request) throws IOException;
        void close();
    }

    /**
     * Stdio-based JSON-RPC transport with persistent reader.
     */
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
        public void close() {
            process.destroy();
            try {
                reader.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
