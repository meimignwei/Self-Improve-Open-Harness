package com.openharness.common;

import java.util.Map;

/**
 * UI-facing stream events — consumed by the frontend via JSON-Lines protocol.
 * Java equivalent of Python's StreamEvent Union type.
 */
public sealed interface StreamEvent
        permits StreamEvent.AssistantTextDelta, StreamEvent.AssistantTurnComplete,
                StreamEvent.ToolStarted, StreamEvent.ToolCompleted,
                StreamEvent.StatusEvent, StreamEvent.CompactProgressEvent,
                StreamEvent.ErrorStreamEvent {

    record AssistantTextDelta(String text) implements StreamEvent {}

    record AssistantTurnComplete(UsageSnapshot usage) implements StreamEvent {}

    record ToolStarted(String toolName, String toolId) implements StreamEvent {}

    record ToolCompleted(String toolName, String toolId, ToolResult result) implements StreamEvent {}

    record StatusEvent(String message, StatusLevel level) implements StreamEvent {}

    /**
     * Compaction progress event matching Python's CompactProgressEvent.
     * phase: hooks_start, context_collapse_start, context_collapse_end,
     *        session_memory_start, session_memory_end, compact_start,
     *        compact_retry, compact_end, compact_failed
     */
    record CompactProgressEvent(
            String phase,
            String trigger,
            String message,
            Integer attempt,
            String checkpoint,
            Map<String, Object> metadata) implements StreamEvent {}

    record ErrorStreamEvent(String message) implements StreamEvent {}

    enum StatusLevel { INFO, WARNING, ERROR }
}
