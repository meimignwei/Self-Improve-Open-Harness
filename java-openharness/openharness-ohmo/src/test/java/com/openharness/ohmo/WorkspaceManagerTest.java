package com.openharness.ohmo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkspaceManager();
    }

    @Test
    void resolveShouldUseGivenPath() {
        var path = manager.resolve(tempDir.toString());
        assertEquals(tempDir.toAbsolutePath(), path);
    }

    @Test
    void resolveShouldDefaultToDotOhmo() {
        var path = manager.resolve(null);
        assertTrue(path.endsWith(".ohmo"));
    }

    @Test
    void initializeShouldCreateRequiredDirs() {
        var root = manager.initialize(tempDir.resolve("ws"));

        assertTrue(Files.exists(root));
        assertTrue(Files.exists(root.resolve("memory")));
        assertTrue(Files.exists(root.resolve("sessions")));
        assertTrue(Files.exists(root.resolve("groups")));
        assertTrue(Files.exists(root.resolve("logs")));
        assertTrue(Files.exists(root.resolve("soul.md")));
    }

    @Test
    void healthCheckShouldReturnStatus() {
        var root = manager.initialize(tempDir.resolve("hc-ws"));
        var results = manager.healthCheck(root);

        assertTrue(results.get("workspace"));
        assertTrue(results.get("soul"));
        assertTrue(results.get("memory_dir"));
        assertTrue(results.get("sessions_dir"));
    }

    @Test
    void getGatewayConfigPathShouldBeWithinWorkspace() {
        var path = manager.getGatewayConfigPath(tempDir.resolve("ws"));
        assertEquals(tempDir.resolve("ws").resolve("gateway.json"), path);
    }

    @Test
    void getGatewayPidPathShouldBeWithinWorkspace() {
        var path = manager.getGatewayPidPath(tempDir.resolve("ws"));
        assertEquals(tempDir.resolve("ws").resolve("gateway.pid"), path);
    }

    @Test
    void getSessionsDirShouldBeWithinWorkspace() {
        var path = manager.getSessionsDir(tempDir.resolve("ws"));
        assertEquals(tempDir.resolve("ws").resolve("sessions"), path);
    }

    @Test
    void getGroupsDirShouldBeWithinWorkspace() {
        var path = manager.getGroupsDir(tempDir.resolve("ws"));
        assertEquals(tempDir.resolve("ws").resolve("groups"), path);
    }

    @Test
    void getLogsDirShouldBeWithinWorkspace() {
        var path = manager.getLogsDir(tempDir.resolve("ws"));
        assertEquals(tempDir.resolve("ws").resolve("logs"), path);
    }
}
