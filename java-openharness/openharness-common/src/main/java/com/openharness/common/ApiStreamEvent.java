package com.openharness.common;

/**
 * LLM API stream events — emitted by API clients and consumed by the engine.
 * Java equivalent of Python's ApiStreamEvent Union type.
 */
public sealed interface ApiStreamEvent
        permits ApiStreamEvent.ContentDelta, ApiStreamEvent.ToolUseStart,
                ApiStreamEvent.ToolUseInputDelta, ApiStreamEvent.ToolUseComplete,
                ApiStreamEvent.TurnComplete, ApiStreamEvent.ErrorEvent {

    record ContentDelta(String text, int index) implements ApiStreamEvent {}

    record ToolUseStart(String id, String name) implements ApiStreamEvent {}

    record ToolUseInputDelta(String id, String inputJson) implements ApiStreamEvent {}

    record ToolUseComplete(String id, String name,
                           com.fasterxml.jackson.databind.JsonNode input) implements ApiStreamEvent {}

    record TurnComplete(UsageSnapshot usage, String stopReason) implements ApiStreamEvent {}

    record ErrorEvent(String code, String message) implements ApiStreamEvent {}
}
