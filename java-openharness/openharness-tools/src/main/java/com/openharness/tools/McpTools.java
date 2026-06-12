package com.openharness.tools;

import com.openharness.common.McpClient;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.util.stream.Collectors;

/**
 * MCP resource tools.
 */
public final class McpTools {

    private McpTools() {}

    public static class ListMcpResourcesTool extends BaseTool<ListMcpResourcesTool.Input> {
        private final McpClient mcpClient;

        public ListMcpResourcesTool(McpClient mcpClient) {
            super("list_mcp_resources", "List available resources from an MCP server.", Input.class);
            this.mcpClient = mcpClient;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            var resources = mcpClient.listResources(arguments.serverName());
            if (resources.isEmpty()) {
                return ToolResult.success("No resources found on MCP server '" + arguments.serverName() + "'.");
            }
            String result = resources.stream()
                    .map(r -> r.name() + " (" + r.uri() + "): " + r.description())
                    .collect(Collectors.joining("\n"));
            return ToolResult.success(result);
        }

        @Override
        public boolean isReadOnly(Input arguments) {
            return true;
        }

        public record Input(String serverName) {
            public Input {
                if (serverName == null || serverName.isBlank()) {
                    throw new IllegalArgumentException("serverName is required");
                }
            }
        }
    }

    public static class ReadMcpResourceTool extends BaseTool<ReadMcpResourceTool.Input> {
        private final McpClient mcpClient;

        public ReadMcpResourceTool(McpClient mcpClient) {
            super("read_mcp_resource", "Read the contents of an MCP resource by URI.", Input.class);
            this.mcpClient = mcpClient;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            String content = mcpClient.readResource(arguments.serverName(), arguments.uri());
            return ToolResult.success(content);
        }

        @Override
        public boolean isReadOnly(Input arguments) {
            return true;
        }

        public record Input(String serverName, String uri) {
            public Input {
                if (serverName == null || serverName.isBlank()) {
                    throw new IllegalArgumentException("serverName is required");
                }
                if (uri == null || uri.isBlank()) {
                    throw new IllegalArgumentException("uri is required");
                }
            }
        }
    }
}
