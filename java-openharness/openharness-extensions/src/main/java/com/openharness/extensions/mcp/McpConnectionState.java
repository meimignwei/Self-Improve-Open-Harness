package com.openharness.extensions.mcp;

import com.openharness.common.McpClient;
import com.openharness.common.McpToolInfo;
import java.util.List;

public record McpConnectionState(
        String name,
        ConnectionState state,
        String detail,
        String transport,
        boolean authConfigured,
        List<McpToolInfo> tools,
        List<McpClient.McpResourceInfo> resources) {

    public static McpConnectionState failed(String name, Exception e) {
        return new McpConnectionState(name, ConnectionState.FAILED,
                e.getMessage(), "unknown", false, List.of(), List.of());
    }
}
