package com.openharness.common;

import java.util.List;

/**
 * Tool execution result record.
 * Java equivalent of Python's ToolResult dataclass.
 */
public record ToolResult(
        String content,
        boolean isError,
        List<MediaFile> mediaFiles,
        java.util.Map<String, Object> metadata) {

    public ToolResult(String content, boolean isError) {
        this(content, isError, List.of(), java.util.Map.of());
    }

    public ToolResult(String content, boolean isError, java.util.Map<String, Object> metadata) {
        this(content, isError, List.of(), metadata);
    }

    public ToolResult(String content, boolean isError, List<MediaFile> mediaFiles) {
        this(content, isError, mediaFiles, java.util.Map.of());
    }

    public static ToolResult success(String content) {
        return new ToolResult(content, false, List.of(), java.util.Map.of());
    }

    public static ToolResult success(String content, java.util.Map<String, Object> metadata) {
        return new ToolResult(content, false, List.of(), metadata);
    }

    public static ToolResult success(String content, List<MediaFile> mediaFiles) {
        return new ToolResult(content, false, mediaFiles, java.util.Map.of());
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true, List.of(), java.util.Map.of());
    }

    /**
     * Represents a media file attached to a tool result (e.g., images from screenshots).
     */
    public record MediaFile(String fileName, String mediaType, String base64Data) {}
}
