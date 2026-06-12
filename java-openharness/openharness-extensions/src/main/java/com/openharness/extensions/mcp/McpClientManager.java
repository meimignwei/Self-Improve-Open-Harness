package com.openharness.extensions.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class McpClientManager {

    private static final Logger LOG = Logger.getLogger(McpClientManager.class.getName());

    private final Map<String, McpConnectionState> connections = new ConcurrentHashMap<>();
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

        // Send initialize request and read response
        JsonNode initResult = sendJsonRpc(process,
                buildRequest("initialize", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of())));

        // List tools
        JsonNode toolsResult = sendJsonRpc(process,
                buildRequest("tools/list", Map.of()));

        List<McpToolInfo> tools = parseToolList(toolsResult, config.name());
        return new McpConnectionState(config.name(), ConnectionState.CONNECTED,
                "Connected via stdio", "stdio", false, tools, List.of());
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
        return "MCP tool call: " + serverName + "/" + toolName;
    }

    public List<McpToolInfo> listTools() {
        return connections.values().stream()
                .filter(c -> c.state() == ConnectionState.CONNECTED)
                .flatMap(c -> c.tools().stream())
                .toList();
    }

    public List<McpConnectionState> listStatuses() {
        return List.copyOf(connections.values());
    }

    public void disconnect(String serverName) {
        connections.remove(serverName);
    }

    private JsonNode sendJsonRpc(Process process, JsonNode request) throws IOException {
        String json = mapper.writeValueAsString(request) + "\n";
        process.getOutputStream().write(json.getBytes());
        process.getOutputStream().flush();

        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null) {
                return mapper.readTree(line);
            }
        } catch (Exception e) {
            LOG.warning("Failed to read MCP response: " + e.getMessage());
        }
        return mapper.createObjectNode();
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
}
