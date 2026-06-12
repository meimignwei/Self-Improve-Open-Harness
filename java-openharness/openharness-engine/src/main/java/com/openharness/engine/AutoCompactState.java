package com.openharness.engine;

import com.openharness.common.ContentBlock;
import com.openharness.common.ConversationMessage;
import com.openharness.common.UsageSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks accumulated token usage and triggers compaction when thresholds are exceeded.
 */
public class AutoCompactState {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int DEFAULT_SOFT_TOKEN_BUDGET = 8000;

    private final int thresholdTokens;
    private int accumulatedTokens = 0;

    public AutoCompactState(int thresholdTokens) {
        this.thresholdTokens = thresholdTokens;
    }

    public boolean shouldCompact(UsageSnapshot usage) {
        if (usage == null) return false;
        accumulatedTokens += usage.totalTokens();
        return thresholdTokens > 0 && accumulatedTokens >= thresholdTokens;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> compact(List<T> conversation) {
        if (conversation == null || conversation.isEmpty()) return conversation;
        accumulatedTokens = 0;
        return (List<T>) microCompact((List<ConversationMessage>) conversation, DEFAULT_SOFT_TOKEN_BUDGET);
    }

    private static List<ConversationMessage> microCompact(List<ConversationMessage> messages, int softTokenBudget) {
        int maxChars = softTokenBudget * CHARS_PER_TOKEN;
        List<ConversationMessage> compacted = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            List<ContentBlock> newContent = new ArrayList<>();
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.ToolResultBlock trb) {
                    String truncated = truncate(trb.content(), maxChars);
                    newContent.add(new ContentBlock.ToolResultBlock(trb.toolUseId(), truncated, trb.isError()));
                } else {
                    newContent.add(block);
                }
            }
            compacted.add(new ConversationMessage(msg.role(), newContent));
        }
        return compacted;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    public int accumulatedTokens() {
        return accumulatedTokens;
    }

    public void reset() {
        accumulatedTokens = 0;
    }
}
