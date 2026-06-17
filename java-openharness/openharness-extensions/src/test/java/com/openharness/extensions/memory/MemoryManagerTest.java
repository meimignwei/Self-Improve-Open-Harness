package com.openharness.extensions.memory;

import com.openharness.config.MemorySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    @TempDir
    Path memoryDir;

    private MemoryManager manager;

    @BeforeEach
    void setUp() {
        var settings = new MemorySettings();
        manager = new MemoryManager(memoryDir, settings);
    }

    @Test
    void createShouldPersistAndReturnEntry() {
        var entry = manager.create(MemoryType.USER, "Test Name", "Test Description", "Test body content");
        assertNotNull(entry.header().id());
        assertEquals("Test Name", entry.header().name());
        assertEquals(MemoryType.USER, entry.header().type());
    }

    @Test
    void getShouldReturnSavedEntry() {
        var created = manager.create(MemoryType.USER, "Get Test", "desc", "body");
        var loaded = manager.get(created.header().id());
        assertNotNull(loaded);
        assertEquals("Get Test", loaded.header().name());
    }

    @Test
    void getShouldReturnNullForUnknownId() {
        assertNull(manager.get("nonexistent-id"));
    }

    @Test
    void updateShouldChangeBody() {
        var created = manager.create(MemoryType.USER, "Update Test", "desc", "original body");
        var updated = manager.update(created.header().id(), "new body");
        assertNotNull(updated);
        assertEquals("new body", updated.body());
        assertNotEquals(created.header().signature(), updated.header().signature());
    }

    @Test
    void updateShouldReturnNullForUnknownId() {
        assertNull(manager.update("unknown", "new body"));
    }

    @Test
    void setImportanceShouldUpdateValue() {
        var created = manager.create(MemoryType.USER, "Importance Test", "desc", "body");
        var updated = manager.setImportance(created.header().id(), 9);
        assertNotNull(updated);
        assertEquals(9, updated.header().importance());
    }

    @Test
    void setImportanceShouldReturnNullForUnknownId() {
        assertNull(manager.setImportance("unknown", 7));
    }

    @Test
    void deleteShouldRemoveEntry() {
        var created = manager.create(MemoryType.USER, "Delete Test", "desc", "body");
        assertTrue(manager.delete(created.header().id()));
        assertNull(manager.get(created.header().id()));
    }

    @Test
    void deleteShouldReturnFalseForUnknownId() {
        assertFalse(manager.delete("unknown"));
    }

    @Test
    void listAllShouldReturnAllEntries() {
        manager.create(MemoryType.USER, "A", "desc", "body");
        manager.create(MemoryType.USER, "B", "desc", "body");
        manager.create(MemoryType.FEEDBACK, "C", "desc", "body");

        var all = manager.listAll();
        assertEquals(3, all.size());
    }

    @Test
    void listByTypeShouldFilter() {
        manager.create(MemoryType.USER, "User Mem", "desc", "body");
        manager.create(MemoryType.FEEDBACK, "Feedback Mem", "desc", "body");

        var users = manager.listByType(MemoryType.USER);
        assertEquals(1, users.size());
        assertEquals("User Mem", users.get(0).header().name());
    }

    @Test
    void createWithMemoryEntryShouldDeduplicate() {
        var entry = MemoryEntry.create(MemoryType.USER, "Dedup", "desc", "body");
        manager.create(entry);
        manager.create(entry);

        assertEquals(1, manager.listAll().size(), "Should deduplicate, only one entry stored");
    }

    @Test
    void pruneExpiredShouldRemoveExpired() {
        var entry = MemoryEntry.create(MemoryType.USER, "Expired", "desc", "body");
        var header = entry.header();
        var expiredHeader = new MemoryEntry.MemoryHeader(
                header.schemaVersion(), MemoryEntry.generateMemoryId(), header.name(),
                header.description(), header.type(), header.scope(), header.category(),
                header.importance(), header.source(),
                MemorySignature.compute(entry.body(), header.type().name().toLowerCase(),
                        header.category() != null ? header.category() : "knowledge"),
                java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS),
                java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS),
                1, false, java.util.List.of(), header.tags());
        var expired = new MemoryEntry(expiredHeader, entry.body());
        // Save directly to preserve expired timestamps (create() would regenerate)
        manager.store().save(expired);

        int count = manager.pruneExpired();
        assertEquals(1, count);
        assertTrue(manager.listAll().isEmpty());
    }

    @Test
    void searchShouldReturnScoredResults() {
        manager.create(MemoryType.USER, "Deployment Guide",
                "How to deploy services", "Detailed deployment steps for production");

        var results = manager.search("deployment", 5);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).score() > 0);
    }

    @Test
    void storeAndUsageTrackerShouldBeAccessible() {
        assertNotNull(manager.store());
        assertNotNull(manager.usageTracker());
    }

    @Test
    void addMemoryEntryShouldCreateAndUpdateIndex() {
        Path path = manager.addMemoryEntry(memoryDir, "Index Test", "Memory content",
                MemoryType.USER, "project", "Test description", List.of());

        assertNotNull(path);
        assertTrue(java.nio.file.Files.exists(path));

        // Check MEMORY.md was created
        Path entrypoint = memoryDir.resolve("MEMORY.md");
        assertTrue(java.nio.file.Files.exists(entrypoint));
        try {
            String content = java.nio.file.Files.readString(entrypoint);
            assertTrue(content.contains("Index Test"));
            assertTrue(content.contains(path.getFileName().toString()));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void addMemoryEntryShouldDeduplicateBySignature() {
        Path path1 = manager.addMemoryEntry(memoryDir, "Dup Test", "Same content here",
                MemoryType.USER, "project", "desc", List.of());
        Path path2 = manager.addMemoryEntry(memoryDir, "Dup Test", "Same content here",
                MemoryType.USER, "project", "desc", List.of());

        assertEquals(path1, path2, "Should reuse same file for duplicate content");
    }

    @Test
    void removeMemoryEntryShouldSoftDelete() {
        Path path = manager.addMemoryEntry(memoryDir, "Remove Test", "Content to remove",
                MemoryType.USER, "project", "desc", List.of());

        // Find entry ID from file
        var loaded = manager.store().parseMemoryFile(path);
        boolean removed = manager.removeMemoryEntry(loaded.header().name());
        assertTrue(removed);

        // Should be soft-deleted (disabled=true)
        var afterRemoval = manager.store().parseMemoryFile(path);
        assertTrue(afterRemoval.header().disabled());

        // MEMORY.md should no longer have the entry
        try {
            String entrypointContent = java.nio.file.Files.readString(memoryDir.resolve("MEMORY.md"));
            assertFalse(entrypointContent.contains(path.getFileName().toString()));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void removeMemoryEntryShouldReturnFalseForAlreadyDisabled() {
        Path path = manager.addMemoryEntry(memoryDir, "Disabled Test", "Content",
                MemoryType.USER, "project", "desc", List.of());
        var loaded = manager.store().parseMemoryFile(path);
        manager.removeMemoryEntry(loaded.header().name());
        // Second removal should return false
        boolean second = manager.removeMemoryEntry(loaded.header().name());
        assertFalse(second);
    }

    @Test
    void removeMemoryEntryShouldReturnFalseForUnknown() {
        assertFalse(manager.removeMemoryEntry("nonexistent-name"));
    }
}
