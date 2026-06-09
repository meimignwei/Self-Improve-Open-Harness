package io.agentscope.core.tool;

import java.util.Collections;
import java.util.Map;

public record ToolResultBlock(boolean isError, String content, Map<String, Object> metadata) {

    public ToolResultBlock {
        if (metadata == null) {
            metadata = Collections.emptyMap();
        }
    }

    public static ToolResultBlock success(String content) {
        return new ToolResultBlock(false, content, Collections.emptyMap());
    }

    public static ToolResultBlock success(String content, Map<String, Object> metadata) {
        return new ToolResultBlock(false, content, metadata);
    }

    public static ToolResultBlock error(String content) {
        return new ToolResultBlock(true, content, Collections.emptyMap());
    }
}
