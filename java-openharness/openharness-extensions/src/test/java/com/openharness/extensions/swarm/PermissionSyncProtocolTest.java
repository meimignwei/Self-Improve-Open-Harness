package com.openharness.extensions.swarm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PermissionSyncProtocolTest {

    private FileMailbox mailbox;
    private PermissionSyncProtocol protocol;

    @BeforeEach
    void setUp() {
        String team = "perm-team-" + System.nanoTime();
        mailbox = new FileMailbox(team, "worker-perm");
        protocol = new PermissionSyncProtocol(team, mailbox, 5_000);
    }

    @Test
    void createPermissionRequestShouldSetFields() {
        var request = protocol.createPermissionRequest("worker-1", "Bash",
                Map.of("command", "ls"));

        assertEquals("worker-1", request.workerId);
        assertEquals("Bash", request.toolName);
        assertEquals("ls", request.arguments.get("command"));
        assertNotNull(request.requestId);
    }

    @Test
    void sendPermissionRequestShouldWriteToMailbox() {
        var request = protocol.createPermissionRequest("worker-1", "Read", Map.of());
        protocol.sendPermissionRequest(request);

        var messages = mailbox.readAll(false);
        assertEquals(1, messages.size());
        assertEquals("permission_request", messages.getFirst().type);
    }

    @Test
    void timeoutShouldReturnDeniedResponse() throws Exception {
        var request = protocol.createPermissionRequest("worker-1", "Bash", Map.of());
        var future = protocol.sendPermissionRequest(request, 500);

        var response = future.get(2_000, TimeUnit.MILLISECONDS);
        assertEquals("denied", response.decision);
        assertEquals("timeout", response.reason);
    }
}
