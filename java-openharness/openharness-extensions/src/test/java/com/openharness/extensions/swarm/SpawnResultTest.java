package com.openharness.extensions.swarm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpawnResultTest {

    @Test
    void successShouldReturnNonErrorResult() {
        var result = SpawnResult.success("task-123", "agent@team", "in_process");

        assertTrue(result.success());
        assertNull(result.error());
        assertEquals("task-123", result.taskId());
        assertEquals("agent@team", result.agentId());
        assertEquals("in_process", result.backendType());
    }

    @Test
    void failureShouldReturnErrorResult() {
        var result = SpawnResult.failure("task-456", "agent@team", "tmux", "tmux not available");

        assertFalse(result.success());
        assertEquals("tmux not available", result.error());
        assertEquals("task-456", result.taskId());
        assertEquals("agent@team", result.agentId());
        assertEquals("tmux", result.backendType());
    }
}
