package com.openharness.common;

/**
 * Sealed interface representing all possible content blocks in a conversation message.
 * Java equivalent of Python's ContentBlock Union type.
 */
public sealed interface ContentBlock
        permits ContentBlock.TextBlock, ContentBlock.ImageBlock,
                ContentBlock.ToolUseBlock, ContentBlock.ToolResultBlock {

    record TextBlock(String text) implements ContentBlock {}

    record ImageBlock(String mediaType, String base64Data, String sourcePath) implements ContentBlock {
        public ImageBlock(String mediaType, String base64Data) {
            this(mediaType, base64Data, "");
        }
    }

    record ToolUseBlock(String id, String name, com.fasterxml.jackson.databind.JsonNode input)
            implements ContentBlock {}

    record ToolResultBlock(String toolUseId, String content, boolean isError)
            implements ContentBlock {}
}
