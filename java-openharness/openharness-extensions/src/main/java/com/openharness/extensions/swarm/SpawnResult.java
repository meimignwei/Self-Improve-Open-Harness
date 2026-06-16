package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Result from spawning a teammate.
 * Java equivalent of Python swarm/types.py SpawnResult.
 */
@JsonDeserialize
public record SpawnResult(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("backend_type") String backendType,
        @JsonProperty("success") boolean success,
        @JsonProperty("error") String error,
        @JsonProperty("pane_id") String paneId) {

    public SpawnResult {
        if (paneId == null) paneId = null;
        if (error == null) error = null;
    }

    public static SpawnResult success(String taskId, String agentId, String backendType, String paneId) {
        return new SpawnResult(taskId, agentId, backendType, true, null, paneId);
    }

    public static SpawnResult success(String taskId, String agentId, String backendType) {
        return new SpawnResult(taskId, agentId, backendType, true, null, null);
    }

    public static SpawnResult failure(String taskId, String agentId, String backendType, String error) {
        return new SpawnResult(taskId, agentId, backendType, false, error, null);
    }
}