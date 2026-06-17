package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Result of creating a new teammate pane.
 * Java equivalent of Python swarm/types.py CreatePaneResult.
 */
@JsonDeserialize
public record CreatePaneResult(
        @JsonProperty("pane_id") String paneId,
        @JsonProperty("is_first_teammate") boolean isFirstTeammate) {
}