package com.openharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.common.McpClient;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Direct MCP tool caller — invokes a tool on a specific MCP server by name.
 * Java equivalent of Python's mcp_tool.
 */
public class McpTool extends BaseTool<McpTool.Input> {

    private final McpClient mcpClient;

    public McpTool(McpClient mcpClient) {
        super("mcp_tool", "Call an MCP tool directly by server name, tool name, and arguments.", Input.class);
        this.mcpClient = mcpClient;
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        try {
            String result = mcpClient.callTool(
                    arguments.serverName(),
                    arguments.toolName(),
                    arguments.arguments());
            return ToolResult.success(result);
        } catch (Exception e) {
            return ToolResult.error("MCP tool call failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
    }

    public record Input(String serverName, String toolName, JsonNode arguments) {
        public Input {
            if (serverName == null || serverName.isBlank()) {
                throw new IllegalArgumentException("serverName is required");
            }
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName is required");
            }
            if (arguments == null) {
                arguments = com.openharness.common.OpenHarnessObjectMapper.get().createObjectNode();
            }
        }
    }
}
