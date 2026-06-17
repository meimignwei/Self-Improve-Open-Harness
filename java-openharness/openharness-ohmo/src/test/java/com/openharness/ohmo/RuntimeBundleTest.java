package com.openharness.ohmo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeBundleTest {

    @Test
    void newBundleShouldInitializeEmptyState() {
        var bundle = new RuntimeBundle("test-key", Path.of("/tmp"), "openai", Path.of("/workspace"));

        assertEquals("test-key", bundle.sessionKey());
        assertEquals(Path.of("/tmp"), bundle.cwd());
        assertEquals("openai", bundle.providerProfile());
        assertTrue(bundle.messages().isEmpty());
        assertEquals(0, bundle.maxTurns());
        assertFalse(bundle.enforceMaxTurns());
        assertNull(bundle.model());
    }

    @Test
    void settersShouldUpdateFields() {
        var bundle = new RuntimeBundle("key", Path.of("/tmp"), "openai", Path.of("/ws"));

        bundle.setModel("claude-sonnet-4-6");
        bundle.setMaxTurns(20);
        bundle.setEnforceMaxTurns(true);
        bundle.setSessionId("session-abc");

        assertEquals("claude-sonnet-4-6", bundle.model());
        assertEquals(20, bundle.maxTurns());
        assertTrue(bundle.enforceMaxTurns());
        assertEquals("session-abc", bundle.sessionId());
    }

    @Test
    void messagesShouldBeModifiable() {
        var bundle = new RuntimeBundle("key", Path.of("/tmp"), "openai", Path.of("/ws"));
        bundle.setMessages(List.of(Map.of("role", "user", "content", "Hello")));

        assertEquals(1, bundle.messages().size());
    }

    @Test
    void toolMetadataShouldBeEmptyInitially() {
        var bundle = new RuntimeBundle("key", Path.of("/tmp"), "openai", Path.of("/ws"));
        assertTrue(bundle.toolMetadata().isEmpty());
    }

    @Test
    void workspaceRootShouldBeSet() {
        var bundle = new RuntimeBundle("key", Path.of("/tmp"), "openai", Path.of("/ws"));
        assertEquals(Path.of("/ws"), bundle.workspaceRoot());
    }
}
