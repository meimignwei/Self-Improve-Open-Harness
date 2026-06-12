package com.openharness.ui;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.common.ToolResult;
import com.openharness.common.UsageSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 17 backend event subtypes — must exactly match TypeScript BackendEvent in types.ts.
 * Sent as JSON-Lines from Java backend to React/Ink frontend.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BackendEvent.ReadyEvent.class, name = "ready"),
        @JsonSubTypes.Type(value = BackendEvent.StateSnapshotEvent.class, name = "state_snapshot"),
        @JsonSubTypes.Type(value = BackendEvent.TasksSnapshotEvent.class, name = "tasks_snapshot"),
        @JsonSubTypes.Type(value = BackendEvent.TranscriptItemEvent.class, name = "transcript_item"),
        @JsonSubTypes.Type(value = BackendEvent.StatusEvent.class, name = "status"),
        @JsonSubTypes.Type(value = BackendEvent.CompactProgressEvent.class, name = "compact_progress"),
        @JsonSubTypes.Type(value = BackendEvent.AssistantDeltaEvent.class, name = "assistant_delta"),
        @JsonSubTypes.Type(value = BackendEvent.AssistantCompleteEvent.class, name = "assistant_complete"),
        @JsonSubTypes.Type(value = BackendEvent.LineCompleteEvent.class, name = "line_complete"),
        @JsonSubTypes.Type(value = BackendEvent.ToolStartedEvent.class, name = "tool_started"),
        @JsonSubTypes.Type(value = BackendEvent.ToolCompletedEvent.class, name = "tool_completed"),
        @JsonSubTypes.Type(value = BackendEvent.ClearTranscriptEvent.class, name = "clear_transcript"),
        @JsonSubTypes.Type(value = BackendEvent.SelectRequestEvent.class, name = "select_request"),
        @JsonSubTypes.Type(value = BackendEvent.ModalRequestEvent.class, name = "modal_request"),
        @JsonSubTypes.Type(value = BackendEvent.ErrorEvent.class, name = "error"),
        @JsonSubTypes.Type(value = BackendEvent.TodoUpdateEvent.class, name = "todo_update"),
        @JsonSubTypes.Type(value = BackendEvent.SwarmStatusEvent.class, name = "swarm_status"),
        @JsonSubTypes.Type(value = BackendEvent.PlanModeChangeEvent.class, name = "plan_mode_change"),
        @JsonSubTypes.Type(value = BackendEvent.ShutdownEvent.class, name = "shutdown")
})
public sealed interface BackendEvent {

    record ReadyEvent(String sessionId) implements BackendEvent {}

    record StateSnapshotEvent(
            String model, String permissionMode, String theme, String cwd,
            boolean vimEnabled, boolean voiceEnabled, boolean fastMode,
            String effort, int passes, int mcpConnected, int mcpFailed) implements BackendEvent {}

    record TasksSnapshotEvent(List<TaskInfo> tasks) implements BackendEvent {
        public record TaskInfo(String id, String type, String status, String description) {}
    }

    record TranscriptItemEvent(
            String role, String type, String text, String toolName,
            JsonNode toolInput, String toolResult, UsageSnapshot usage,
            Instant timestamp) implements BackendEvent {}

    record StatusEvent(String message, String level) implements BackendEvent {}

    record CompactProgressEvent(int currentTokens, int targetTokens, String stage) implements BackendEvent {}

    record AssistantDeltaEvent(String text, int turnIndex) implements BackendEvent {}

    record AssistantCompleteEvent(UsageSnapshot usage, int turnIndex) implements BackendEvent {}

    record LineCompleteEvent(String line) implements BackendEvent {}

    record ToolStartedEvent(String toolName, JsonNode arguments, String toolUseId) implements BackendEvent {}

    record ToolCompletedEvent(String toolName, String toolUseId, ToolResult result) implements BackendEvent {}

    record ClearTranscriptEvent() implements BackendEvent {}

    record SelectRequestEvent(String id, String message, List<String> options) implements BackendEvent {}

    record ModalRequestEvent(String id, String title, String content, List<String> buttons) implements BackendEvent {}

    record ErrorEvent(String message, String code, String stack) implements BackendEvent {}

    record TodoUpdateEvent(List<TodoEntry> todos) implements BackendEvent {
        public record TodoEntry(String id, String subject, String status, int priority) {}
    }

    record SwarmStatusEvent(Map<String, String> agentStatuses) implements BackendEvent {}

    record PlanModeChangeEvent(boolean enabled, String planContent) implements BackendEvent {}

    record ShutdownEvent(String reason) implements BackendEvent {}
}
