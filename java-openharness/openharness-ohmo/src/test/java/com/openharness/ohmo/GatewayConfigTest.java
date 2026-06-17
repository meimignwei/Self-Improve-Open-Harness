package com.openharness.ohmo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GatewayConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsShouldHaveReasonableValues() {
        var config = GatewayConfig.defaults();

        assertEquals("chat-thread", config.sessionRouting());
        assertTrue(config.sendProgress());
        assertTrue(config.sendToolHints());
        assertEquals("default", config.permissionMode());
        assertFalse(config.sandboxEnabled());
        assertFalse(config.allowRemoteAdminCommands());
    }

    @Test
    void saveAndLoadShouldRoundtrip() throws Exception {
        Path configPath = tempDir.resolve("gateway.json");
        var original = GatewayConfig.defaults();
        original.saveToWorkspace(tempDir);

        assertTrue(Files.exists(configPath));

        var loaded = GatewayConfig.loadFromWorkspace(tempDir);
        assertEquals(original.sessionRouting(), loaded.sessionRouting());
        assertEquals(original.sendProgress(), loaded.sendProgress());
        assertEquals(original.permissionMode(), loaded.permissionMode());
    }

    @Test
    void loadFromWorkspaceShouldReturnDefaultsWhenNoFile() {
        var loaded = GatewayConfig.loadFromWorkspace(tempDir);
        assertNotNull(loaded);
        assertEquals(GatewayConfig.defaults().sessionRouting(), loaded.sessionRouting());
    }

    @Test
    void allowedRemoteAdminCommandsShouldDefaultToStatusAndStop() {
        var config = GatewayConfig.defaults();
        assertTrue(config.allowedRemoteAdminCommands().contains("status"));
        assertTrue(config.allowedRemoteAdminCommands().contains("stop"));
    }
}
