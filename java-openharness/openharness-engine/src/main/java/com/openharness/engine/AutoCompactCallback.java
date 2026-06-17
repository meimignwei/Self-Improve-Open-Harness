package com.openharness.engine;

import com.openharness.common.ConversationMessage;

import java.util.List;

/**
 * Callback interface for auto-compaction, matched to Python's auto_compact_if_needed().
 * Implementations wire the real CompactionService without creating module cycles.
 */
@FunctionalInterface
public interface AutoCompactCallback {

    /**
     * Check if auto-compact should fire, and if so, compact.
     *
     * @param messages current conversation
     * @param model    the model being used
     * @return (compactedMessages, wasCompacted)
     */
    CompactResult apply(List<ConversationMessage> messages, String model);

    record CompactResult(List<ConversationMessage> messages, boolean wasCompacted) {
        public static CompactResult unchanged(List<ConversationMessage> messages) {
            return new CompactResult(messages, false);
        }
        public static CompactResult compacted(List<ConversationMessage> messages) {
            return new CompactResult(messages, true);
        }
    }
}
