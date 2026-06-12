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
}
