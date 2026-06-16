package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Message to send to a teammate.
 * Java equivalent of Python swarm/types.py TeammateMessage.
 */
@JsonDeserialize
public record TeammateMessage(
        @JsonProperty("text") String text,
        @JsonProperty("from_agent") String fromAgent,
        @JsonProperty("color") String color,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("summary") String summary) {

    public TeammateMessage {
        if (color == null) color = null;
        if (timestamp == null) timestamp = null;
        if (summary == null) summary = null;
    }

    public TeammateMessage(String text, String fromAgent) {
        this(text, fromAgent, null, null, null);
    }
}