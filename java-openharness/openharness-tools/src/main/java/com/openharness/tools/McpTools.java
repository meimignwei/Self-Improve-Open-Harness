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

    /**
     * List MCP resources from ALL connected servers (matching Python's ListMcpResourcesTool).
     * Java extension: also supports listing from a single server when serverName is provided.
     */
    public static class ListMcpResourcesTool extends BaseTool<ListMcpResourcesTool.Input> {
        private final McpClient mcpClient;

        public ListMcpResourcesTool(McpClient mcpClient) {
            super("list_mcp_resources",
                    "List MCP resources available from connected servers. "
                            + "Optionally filter by serverName to list resources from a single server.",
                    Input.class);
            this.mcpClient = mcpClient;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            var resources = arguments.serverName() != null && !arguments.serverName().isBlank()
                    ? mcpClient.listResources(arguments.serverName())
                    : mcpClient.listResources();
            if (resources.isEmpty()) {
                return ToolResult.success("(no MCP resources)");
            }
            // Python format: {server_name}:{uri} {description}
            String result = resources.stream()
                    .map(r -> r.serverName() + ":" + r.uri() + " " + r.description())
                    .collect(Collectors.joining("\n"));
            return ToolResult.success(result);
        }

        @Override
        public boolean isReadOnly(Input arguments) {
            return true;
        }

        public record Input(String serverName) {
            public Input {
                // serverName is optional (matching Python which has no params)
            }
        }
    }

    public static class ReadMcpResourceTool extends BaseTool<ReadMcpResourceTool.Input> {
        private final McpClient mcpClient;

        public ReadMcpResourceTool(McpClient mcpClient) {
            super("read_mcp_resource", "Read an MCP resource by server and URI.", Input.class);
            this.mcpClient = mcpClient;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            String content = mcpClient.readResource(arguments.server(), arguments.uri());
            return ToolResult.success(content);
        }

        @Override
        public boolean isReadOnly(Input arguments) {
            return true;
        }

        public record Input(String server, String uri) {
            public Input {
                if (server == null || server.isBlank()) {
                    throw new IllegalArgumentException("server is required");
                }
                if (uri == null || uri.isBlank()) {
                    throw new IllegalArgumentException("uri is required");
                }
            }
        }
    }

    // ── MCP Prompts ────────────────────────────────────────────────

    public static class ListMcpPromptsTool extends BaseTool<ListMcpPromptsTool.Input> {
        private final McpClient mcpClient;

        public ListMcpPromptsTool(McpClient mcpClient) {
            super("list_mcp_prompts", "List available prompt templates from an MCP server.", Input.class);
            this.mcpClient = mcpClient;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            var prompts = mcpClient.listPrompts(arguments.serverName());
            if (prompts.isEmpty()) {
                return ToolResult.success("No prompts found on MCP server '" + arguments.serverName() + "'.");
            }
            String result = prompts.stream()
                    .map(p -> {
                        StringBuilder sb = new StringBuilder();
                        sb.append(p.name()).append(": ").append(p.description());
                        if (!p.arguments().isEmpty()) {
                            sb.append(" (args: ");
                            sb.append(p.arguments().stream()
                                    .map(a -> a.name() + (a.required() ? "*" : ""))
                                    .collect(Collectors.joining(", ")));
                            sb.append(")");
                        }
                        return sb.toString();
                    })
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

    public static class GetMcpPromptTool extends BaseTool<GetMcpPromptTool.Input> {
        private final McpClient mcpClient;

        public GetMcpPromptTool(McpClient mcpClient) {
            super("get_mcp_prompt", "Get a resolved prompt from an MCP server by name with optional arguments.", Input.class);
            this.mcpClient = mcpClient;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            String content = mcpClient.getPrompt(arguments.serverName(), arguments.promptName(),
                    arguments.arguments() != null ? arguments.arguments() : java.util.Map.of());
            return ToolResult.success(content);
        }

        @Override
        public boolean isReadOnly(Input arguments) {
            return true;
        }

        public record Input(String serverName, String promptName,
                            java.util.Map<String, String> arguments) {
            public Input {
                if (serverName == null || serverName.isBlank()) {
                    throw new IllegalArgumentException("serverName is required");
                }
                if (promptName == null || promptName.isBlank()) {
                    throw new IllegalArgumentException("promptName is required");
                }
            }
        }
    }
}
