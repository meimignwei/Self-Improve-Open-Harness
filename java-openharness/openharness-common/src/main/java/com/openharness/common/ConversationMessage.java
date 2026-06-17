package com.openharness.common;

import java.util.ArrayList;
import java.util.List;

/**
 * A single conversation message containing a role and a list of content blocks.
 * Java record equivalent of Python's ConversationMessage Pydantic model.
 */
public record ConversationMessage(Role role, List<ContentBlock> content) {

    public static ConversationMessage fromUserText(String text) {
        return new ConversationMessage(Role.USER, List.of(new ContentBlock.TextBlock(text)));
    }

    public static ConversationMessage fromUserContent(List<ContentBlock> content) {
        return new ConversationMessage(Role.USER, List.copyOf(content));
    }

    /** Return concatenated text from all TextBlocks. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.TextBlock tb) {
                sb.append(tb.text());
            }
        }
        return sb.toString();
    }

    /** Return all ToolUseBlocks in this message. */
    public List<ContentBlock.ToolUseBlock> toolUses() {
        List<ContentBlock.ToolUseBlock> result = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.ToolUseBlock tub) {
                result.add(tub);
            }
        }
        return result;
    }

    /** Return all ToolResultBlocks in this message. */
    public List<ContentBlock.ToolResultBlock> toolResults() {
        List<ContentBlock.ToolResultBlock> result = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.ToolResultBlock trb) {
                result.add(trb);
            }
        }
        return result;
    }

    /** True when the message carries no useful content. */
    public boolean isEffectivelyEmpty() {
        if (content.isEmpty()) return true;
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.TextBlock tb && !tb.text().isBlank()) return false;
            if (block instanceof ContentBlock.ImageBlock) return false;
            if (block instanceof ContentBlock.ToolUseBlock) return false;
            if (block instanceof ContentBlock.ToolResultBlock) return false;
        }
        return true;
    }

    public ConversationMessage withContent(List<ContentBlock> newContent) {
        return new ConversationMessage(role, newContent);
    }
}
