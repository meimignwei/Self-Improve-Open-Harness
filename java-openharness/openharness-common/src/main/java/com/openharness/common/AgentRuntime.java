package com.openharness.common;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * Agent execution interface.
 * Breaks circular dependency: AgentTool depends on this interface, not QueryEngine.
 */
public interface AgentRuntime {
    Flow.Publisher<StreamEvent> runQuery(
            List<ConversationMessage> messages,
            QueryOptions options);

    /**
     * Trigger manual compaction. Default is no-op.
     * @return status message describing what was compacted
     */
    default String compact() {
        return "Compaction not supported by this runtime.";
    }
}
