package com.openharness.extensions.swarm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InProcessBackendTest {

    @TempDir
    Path tempDir;

    private InProcessBackend backend;

    @BeforeEach
    void setUp() {
        // No AgentRuntime — backend runs in stub mode
        backend = new InProcessBackend(null);
    }

    @Test
    void typeShouldReturnInProcess() {
        assertEquals("in_process", backend.type());
    }

    @Test
    void isAvailableShouldReturnTrue() {
        assertTrue(backend.isAvailable());
    }

    @Test
    void spawnShouldCreateTeammate() {
        var spec = TeammateSpec.builder()
                .name("test-agent")
                .team("test-team")
                .prompt("Run analysis")
                .build();

        var result = backend.spawn(spec);

        assertTrue(result.success());
        assertEquals("in_process", result.backendType());
        assertTrue(result.taskId().startsWith("in_process_"));
    }

    @Test
    void spawnShouldFailForDuplicateAgent() {
        var spec = TeammateSpec.builder()
                .name("dup-agent")
                .team("test-team")
                .prompt("Task 1")
                .build();

        var r1 = backend.spawn(spec);
        var r2 = backend.spawn(spec);

        assertTrue(r1.success());
        assertFalse(r2.success());
        assertTrue(r2.error().contains("already running"));
    }

    @Test
    void getStatusShouldReturnUnknownForMissing() {
        var status = backend.getStatus("nonexistent@team");
        assertEquals(TeammateStatus.State.UNKNOWN, status.state());
    }

    @Test
    void isActiveShouldReturnFalseForMissing() {
        assertFalse(backend.isActive("none@team"));
    }

    @Test
    void activeAgentsShouldReturnList() {
        var agents = backend.activeAgents();
        assertNotNull(agents);
        assertTrue(agents.isEmpty());
    }

    @Test
    void shutdownShouldReturnFalseForMissing() {
        assertFalse(backend.shutdown("none@team", false));
    }

    @Test
    void listTeammatesShouldReturnList() {
        var list = backend.listTeammates();
        assertNotNull(list);
    }

    @Test
    void sendMessageShouldNotThrow() {
        assertDoesNotThrow(() ->
                backend.sendMessage("agent@team",
                        new TeammateMessage("Hello", "sender")));
    }

    @Test
    void spawnWithSpacesInNameShouldWork() {
        var spec = TeammateSpec.builder()
                .name("agent with spaces")
                .team("test team")
                .prompt("Task")
                .build();

        var result = backend.spawn(spec);
        assertTrue(result.success());
        assertTrue(result.agentId().contains("agent with spaces@test team"));
    }
}
