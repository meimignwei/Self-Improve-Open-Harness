package com.openharness.extensions.swarm;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeammateSpecTest {

    @Test
    void builderShouldSetRequiredFields() {
        var spec = TeammateSpec.builder()
                .name("test-agent")
                .team("test-team")
                .prompt("Do something")
                .build();

        assertEquals("test-agent", spec.name());
        assertEquals("test-team", spec.team());
        assertEquals("Do something", spec.prompt());
    }

    @Test
    void builderShouldSetOptionalFields() {
        var spec = TeammateSpec.builder()
                .name("agent")
                .team("default")
                .prompt("Hello")
                .model("claude-sonnet-4-6")
                .command("java -jar agent.jar")
                .cwd("/tmp")
                .systemPrompt("You are helpful")
                .permissions(List.of("read", "write"))
                .sessionId("session-123")
                .parentSessionId("parent-456")
                .color("blue")
                .planModeRequired(true)
                .leaderMailboxPath(Path.of("/tmp/mailbox"))
                .taskType("local_agent")
                .env(Map.of("KEY", "VALUE"))
                .build();

        assertEquals("claude-sonnet-4-6", spec.model());
        assertEquals("java -jar agent.jar", spec.command());
        assertEquals("/tmp", spec.cwd());
        assertEquals("You are helpful", spec.systemPrompt());
        assertEquals(List.of("read", "write"), spec.permissions());
        assertEquals("session-123", spec.sessionId());
        assertEquals("parent-456", spec.parentSessionId());
        assertEquals("blue", spec.color());
        assertTrue(spec.planModeRequired());
        assertEquals("/tmp/mailbox", spec.leaderMailboxPath().toString());
        assertEquals("local_agent", spec.taskType());
        assertEquals(Map.of("KEY", "VALUE"), spec.env());
    }

    @Test
    void builderWithDefaultsShouldNotThrow() {
        var spec = TeammateSpec.builder()
                .name("basic")
                .team("team")
                .prompt("run")
                .build();

        assertNull(spec.model());
        assertNull(spec.command());
        assertEquals(List.of(), spec.permissions());
        assertFalse(spec.planModeRequired());
        assertEquals(Map.of(), spec.env());
        assertEquals("local_agent", spec.taskType());
    }

    @Test
    void agentTypeShouldReturnName() {
        var spec = TeammateSpec.builder()
                .name("worker-agent")
                .team("dev")
                .prompt("Task")
                .build();

        assertEquals("worker-agent", spec.agentType());
    }
}
