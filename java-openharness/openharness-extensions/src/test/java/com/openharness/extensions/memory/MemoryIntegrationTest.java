package com.openharness.extensions.memory;

import com.openharness.config.MemorySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for memory CRUD through MemoryFileStore + MemoryManager.
 */
class MemoryIntegrationTest {

    private MemoryFileStore store;
    private MemoryManager manager;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        store = new MemoryFileStore(tempDir);
        manager = new MemoryManager(tempDir, new MemorySettings());
    }

    @Test
    void writeAndReadMemory() {
        manager.create(MemoryEntry.create(
                MemoryType.USER, "user_role",
                "User role information",
                "User is a senior Java developer"));

        var results = manager.search("Java developer", 5);
        assertFalse(results.isEmpty());
        assertTrue(results.getFirst().memory().body().contains("Java developer"));
    }

    @Test
    void listByType() {
        manager.create(MemoryEntry.create(
                MemoryType.PROJECT, "project_info",
                "Project info", "Maven multi-module project"));
        manager.create(MemoryEntry.create(
                MemoryType.FEEDBACK, "feedback_1",
                "Feedback", "Use concise commits"));

        var projectResults = manager.listByType(MemoryType.PROJECT);
        assertEquals(1, projectResults.size());
        assertEquals("project_info", projectResults.getFirst().header().name());
    }

    @Test
    void deleteMemory() {
        var entry = manager.create(MemoryEntry.create(
                MemoryType.REFERENCE, "ref_1",
                "Reference", "Some reference content"));

        var results = manager.search("reference", 5);
        assertFalse(results.isEmpty());

        manager.delete(entry.header().id());
        var afterDelete = manager.listByType(MemoryType.REFERENCE);
        assertTrue(afterDelete.isEmpty());
    }

    @Test
    void listAllMemories() {
        manager.create(MemoryEntry.create(
                MemoryType.USER, "user_1",
                "User memory 1", "Content 1"));
        manager.create(MemoryEntry.create(
                MemoryType.PROJECT, "project_1",
                "Project memory 1", "Content 2"));

        List<MemoryEntry> all = manager.listAll();
        assertEquals(2, all.size());
    }

    @Test
    void searchRanksMatchingHigherThanNonMatching() {
        manager.create(MemoryEntry.create(
                MemoryType.USER, "java_tips",
                "Java tips", "Use Streams API for collections"));
        manager.create(MemoryEntry.create(
                MemoryType.USER, "general_note",
                "General note", "Remember to commit often"));

        var results = manager.search("Java Streams", 5);
        assertFalse(results.isEmpty());
        // The Java-related memory should score higher than the general note
        assertEquals("java_tips", results.getFirst().memory().header().name());
    }

    @Test
    void searchReturnsResultsForMatchingQuery() {
        manager.create(MemoryEntry.create(
                MemoryType.USER, "test",
                "Test", "Java testing"));

        // Matching query should return results with relevance score
        var results = manager.search("Java", 5);
        assertFalse(results.isEmpty());
        assertTrue(results.getFirst().score() > 0,
                "Score should be > 0 for matching query, got: " + results.getFirst().score());
    }

    @Test
    void searchReturnsEmptyForNonmatchingQuery() {
        manager.create(MemoryEntry.create(
                MemoryType.USER, "test",
                "Test", "Java testing"));

        // Non-matching query returns nothing (Python behavior: only entries with hits)
        var results = manager.search("nonexistent_xyz_123", 5);
        assertTrue(results.isEmpty(),
                "Non-matching query should return empty, got: " + results.size() + " results");
    }

    @Test
    void memoryFileStoreRoundTrip() {
        var entry = MemoryEntry.create(
                MemoryType.USER, "test_name",
                "Test description", "Test body content");

        store.save(entry);
        assertTrue(store.exists(entry.header().id()));

        var loaded = store.loadById(entry.header().id());
        assertNotNull(loaded);
        assertEquals("test_name", loaded.header().name());
        assertEquals("Test body content", loaded.body());
    }

    @Test
    void memoryWithCustomHeader() {
        var header = MemoryEntry.MemoryHeader.builder()
                .name("custom_memory")
                .type(MemoryType.PROJECT)
                .description("Custom description")
                .importance(8)
                .build();
        var entry = new MemoryEntry(header, "Custom body");

        manager.create(entry);
        var results = manager.search("Custom", 5);
        assertFalse(results.isEmpty());
        assertEquals("custom_memory", results.getFirst().memory().header().name());
    }
}
