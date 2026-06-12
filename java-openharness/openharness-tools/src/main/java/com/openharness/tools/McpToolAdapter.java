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
        String server = info.serverName().replaceAll("[^a-zA-Z0-9_]", "_");
        String name = info.name().replaceAll("[^a-zA-Z0-9_]", "_");
        return "mcp_" + server + "_" + name;
    }

    private static String buildDescription(McpToolInfo info) {
        String desc = info.description();
        if (desc == null || desc.isBlank()) {
            desc = "MCP tool from server " + info.serverName();
        }
        return "[MCP:" + info.serverName() + "] " + desc;
    }
}
