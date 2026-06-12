package com.openharness.extensions.memory;

import com.openharness.config.MemorySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
                header.schemaVersion(), UUID.randomUUID().toString(), header.name(),
                header.description(), header.type(), header.category(),
                header.importance(), header.source(),
                MemorySignature.compute(header.name(), entry.body()),
                java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS),
                java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS),
                1, false, java.util.List.of());
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
}
