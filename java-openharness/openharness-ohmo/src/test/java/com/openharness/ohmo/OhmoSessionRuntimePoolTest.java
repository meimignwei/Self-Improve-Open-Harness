package com.openharness.ohmo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OhmoSessionRuntimePoolTest {

    @TempDir
    Path tempDir;

    private Path workspaceRoot;
    private OhmoSessionRuntimePool pool;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspace");
        new WorkspaceManager().initialize(workspaceRoot);
        pool = new OhmoSessionRuntimePool(workspaceRoot, "claude", null, null);
    }

    @Test
    void constructShouldInitializeBundles() {
        assertNotNull(pool);
        assertEquals(0, pool.activeSessions());
    }

    @Test
    void getBundleShouldCreateNewBundle() {
        var bundle = pool.getBundle("test-key", "Hello world", null);
        assertNotNull(bundle);
        assertEquals("test-key", bundle.sessionKey());
    }

    @Test
    void getBundleShouldReuseExistingBundle() {
        var b1 = pool.getBundle("key-1", "First message", null);
        var b2 = pool.getBundle("key-1", "Second message", null);

        assertSame(b1, b2);
        assertEquals(1, pool.activeSessions());
    }

    @Test
    void getBundleShouldCreateNewBundleForDifferentCwd() {
        var b1 = pool.getBundle("key-1", "Hello", null);
        var b2 = pool.getBundle("key-1", "Hello", tempDir.resolve("other-dir"));

        assertNotSame(b1, b2);
    }
}
