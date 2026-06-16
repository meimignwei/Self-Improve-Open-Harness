package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Identity fields for a teammate agent.
 * Java equivalent of Python swarm/types.py TeammateIdentity.
 */
@JsonDeserialize
public record TeammateIdentity(
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("name") String name,
        @JsonProperty("team") String team,
        @JsonProperty("color") String color,
        @JsonProperty("parent_session_id") String parentSessionId) {

    public TeammateIdentity {
        if (color == null) color = null;
        if (parentSessionId == null) parentSessionId = null;
    }

    public TeammateIdentity(String agentId, String name, String team) {
        this(agentId, name, team, null, null);
    }
}
