package com.openharness.ohmo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GroupRegistryTest {

    @TempDir
    Path tempDir;

    private Path workspaceRoot;
    private GroupRegistry registry;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspace");
        registry = new GroupRegistry(workspaceRoot);
    }

    @Test
    void saveRecordShouldCreateFile() {
        registry.saveRecord("feishu", "chat-12345", "user-1",
                "Alice Group", "/app", null, "bound",
                Map.of("description", "Test group"));

        Path expectedPath = workspaceRoot.resolve("groups").resolve("feishu")
                .resolve("chat-12345.json");
        assertTrue(Files.exists(expectedPath));
    }

    @Test
    void loadRecordShouldReturnSavedRecord() {
        registry.saveRecord("feishu", "chat-67890", "user-1",
                "Test", "/app", null, "bound", Map.of("key", "value"));

        var loaded = registry.loadRecord("feishu", "chat-67890");
        assertNotNull(loaded);
        assertEquals("Test", loaded.get("name"));
    }

    @Test
    void listRecordsShouldReturnAllForChannel() {
        registry.saveRecord("feishu", "chat-a", "u1",
                "A", "/a", null, "bound", Map.of());
        registry.saveRecord("feishu", "chat-b", "u1",
                "B", "/b", null, "bound", Map.of());

        var records = registry.listRecords("feishu");
        assertEquals(2, records.size());
    }

    @Test
    void deleteRecordShouldRemoveFile() {
        registry.saveRecord("feishu", "chat-del", "u1",
                "Delete Me", "/tmp", null, "bound", Map.of());

        assertTrue(registry.deleteRecord("feishu", "chat-del"));

        var loaded = registry.loadRecord("feishu", "chat-del");
        assertNull(loaded);
    }

    @Test
    void loadRecordShouldReturnNullForMissing() {
        var loaded = registry.loadRecord("unknown", "nonexistent");
        assertNull(loaded);
    }

    @Test
    void normalizeGroupNameShouldThrowOnTooLong() {
        String longName = "A".repeat(200);
        assertThrows(IllegalArgumentException.class,
                () -> GroupRegistry.normalizeGroupName(longName));
    }

    @Test
    void normalizeGroupNameShouldThrowOnEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> GroupRegistry.normalizeGroupName("   "));
    }

    @Test
    void normalizeGroupNameShouldTrim() {
        assertEquals("Test Group", GroupRegistry.normalizeGroupName("  Test Group  "));
    }

    @Test
    void normalizeCwdShouldReturnAbsolutePath() {
        String cwd = GroupRegistry.normalizeCwd("/tmp/test");
        assertTrue(Path.of(cwd).isAbsolute());
    }
}
