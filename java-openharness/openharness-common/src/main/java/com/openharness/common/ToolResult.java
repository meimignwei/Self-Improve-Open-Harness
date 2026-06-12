package com.openharness.common;

import java.util.List;

/**
 * Tool execution result record.
 * Java equivalent of Python's ToolResult Pydantic model.
 */
public record ToolResult(
        String content,
        boolean isError,
        List<MediaFile> mediaFiles) {

    public ToolResult(String content, boolean isError) {
        this(content, isError, List.of());
    }

    public static ToolResult success(String content) {
        return new ToolResult(content, false, List.of());
    }

    public static ToolResult success(String content, List<MediaFile> mediaFiles) {
        return new ToolResult(content, false, mediaFiles);
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true, List.of());
    }

    /**
     * Represents a media file attached to a tool result (e.g., images from screenshots).
     */
    public record MediaFile(String fileName, String mediaType, String base64Data) {}
}
