package com.openharness.common;

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

    record CompactProgressEvent(int removedMessages, int remainingTokens) implements StreamEvent {}

    record ErrorStreamEvent(String message) implements StreamEvent {}

    enum StatusLevel { INFO, WARNING, ERROR }
}
