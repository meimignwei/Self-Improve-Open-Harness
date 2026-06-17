package com.openharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.common.McpClient;
import com.openharness.common.McpToolInfo;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Adapts an MCP tool into an OpenHarness BaseTool so it can be registered
 * dynamically in the ToolRegistry and invoked by the LLM.
 */
public class McpToolAdapter extends BaseTool<JsonNode> {

    private final McpClient mcpClient;
    private final McpToolInfo toolInfo;
    private final JsonNode schema;

    public McpToolAdapter(McpClient mcpClient, McpToolInfo toolInfo) {
        super(sanitizeName(toolInfo), buildDescription(toolInfo), JsonNode.class);
        this.mcpClient = mcpClient;
        this.toolInfo = toolInfo;
        this.schema = toolInfo.inputSchema() != null
                ? toolInfo.inputSchema()
                : com.openharness.common.OpenHarnessObjectMapper.get().createObjectNode();
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        try {
            String result = mcpClient.callTool(
                    toolInfo.serverName(),
                    toolInfo.name(),
                    arguments);
            return ToolResult.success(result);
        } catch (Exception e) {
            return ToolResult.error("MCP tool '" + toolInfo.name() + "' failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(JsonNode arguments) {
        return false;
    }

    @Override
    public JsonNode inputSchema() {
        return schema;
    }

    private static String sanitizeName(McpToolInfo info) {
        String server = sanitizeSegment(info.serverName());
        String name = sanitizeSegment(info.name());
        return "mcp__" + server + "__" + name;
    }

    /**
     * Sanitize a tool name segment matching Python's _sanitize_tool_segment.
     */
    private static String sanitizeSegment(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isEmpty()) {
            return "tool";
        }
        if (!Character.isLetter(sanitized.charAt(0))) {
            return "mcp_" + sanitized;
        }
        return sanitized;
    }

    private static String buildDescription(McpToolInfo info) {
        String desc = info.description();
        if (desc == null || desc.isBlank()) {
            desc = "MCP tool " + info.name();
        }
        var sb = new StringBuilder("[MCP:").append(info.serverName()).append("] ").append(desc);
        // Include input schema so the LLM sees the tool's actual parameters
        if (info.inputSchema() != null && !info.inputSchema().isEmpty()) {
            sb.append("\nParameters: ").append(info.inputSchema().toString());
        }
        return sb.toString();
    }
}
