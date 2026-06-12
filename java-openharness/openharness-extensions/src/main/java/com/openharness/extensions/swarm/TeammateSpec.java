package com.openharness.extensions.swarm;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Map;

/**
 * Specifications for spawning a teammate agent.
 * Java equivalent of Python swarm/types.py TeammateSpec.
 */
public record TeammateSpec(
        String id,
        String agentType,
        String model,
        String systemPrompt,
        Path leaderMailboxPath,
        Path worktreePath,
        Map<String, String> env,
        JsonNode config
) {
    public TeammateSpec {
        if (id == null) id = java.util.UUID.randomUUID().toString();
        if (env == null) env = Map.of();
    }
}
