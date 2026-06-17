package com.openharness.extensions.memory;

import com.openharness.config.AtomicFileWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MemoryFileStoreTest {

    @TempDir
    Path memoryDir;

    private MemoryFileStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryFileStore(memoryDir);
    }

    @Test
    void saveAndLoadById() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test Memory", "A description", "## Body\n\nContent here.");
        store.save(entry);

        var loaded = store.loadById(entry.header().id());
        assertNotNull(loaded);
        assertEquals(entry.header().name(), loaded.header().name());
        assertEquals(entry.header().type(), loaded.header().type());
        assertEquals(entry.body(), loaded.body());
    }

    @Test
    void loadByIdMissingShouldReturnNull() {
        assertNull(store.loadById("nonexistent-id"));
    }

    @Test
    void saveShouldPersistAndOverwrite() {
        var entry = MemoryEntry.create(MemoryType.USER, "Original", "desc", "body v1");
        store.save(entry);

        var updated = entry.withUpdatedBody("body v2");
        store.save(updated);

        var loaded = store.loadById(entry.header().id());
        assertEquals("body v2", loaded.body());
    }

    @Test
    void deleteShouldRemoveFile() {
        var entry = MemoryEntry.create(MemoryType.USER, "ToDelete", "desc", "body");
        store.save(entry);
        assertTrue(store.exists(entry.header().id()));

        assertTrue(store.delete(entry.header().id()));
        assertFalse(store.exists(entry.header().id()));
        assertFalse(store.delete(entry.header().id())); // already gone
    }

    @Test
    void findByTypeShouldFilter() {
        var userEntry = MemoryEntry.create(MemoryType.USER, "User Mem", "desc", "body");
        var feedbackEntry = MemoryEntry.create(MemoryType.FEEDBACK, "Feedback Mem", "desc", "body");
        store.save(userEntry);
        store.save(feedbackEntry);

        var users = store.findByType(MemoryType.USER);
        assertEquals(1, users.size());
        assertEquals("User Mem", users.get(0).header().name());

        var feedbacks = store.findByType(MemoryType.FEEDBACK);
        assertEquals(1, feedbacks.size());
    }

    @Test
    void findBySignatureShouldDeduplicate() {
        var e1 = MemoryEntry.create(MemoryType.USER, "Same Name", "desc", "same body");
        store.save(e1);

        var e2 = MemoryEntry.create(MemoryType.USER, "Same Name", "desc", "same body");
        var results = store.findBySignature(e2.header().signature());
        assertEquals(1, results.size());
    }

    @Test
    void loadAllShouldReturnAllEntries() {
        store.save(MemoryEntry.create(MemoryType.USER, "A", "desc", "body"));
        store.save(MemoryEntry.create(MemoryType.USER, "B", "desc", "body"));
        store.save(MemoryEntry.create(MemoryType.FEEDBACK, "C", "desc", "body"));

        var all = store.loadAll();
        assertEquals(3, all.size());
    }

    @Test
    void parseMarkdownShouldHandleFrontmatterAndBody() {
        var entry = MemoryEntry.create(MemoryType.USER, "Parsed", "desc", "markdown body");
        String serialized = store.renderMemoryFile(entry);
        MemoryEntry parsed = store.parseMarkdown(serialized);

        assertEquals(entry.header().name(), parsed.header().name());
        assertEquals(entry.header().type(), parsed.header().type());
        assertEquals(entry.body(), parsed.body());
    }

    @Test
    void parseMarkdownShouldHandleBodyOnly() {
        MemoryEntry parsed = store.parseMarkdown("Just a body without frontmatter.");
        assertEquals("Just a body without frontmatter.", parsed.body());
    }

    @Test
    void parseMarkdownShouldHandleEmptyFrontmatter() {
        MemoryEntry parsed = store.parseMarkdown("---\n---\nBody after empty frontmatter.");
        assertEquals("Body after empty frontmatter.", parsed.body());
    }

    @Test
    void renderMemoryFileShouldIncludeAllFields() {
        var entry = MemoryEntry.create(MemoryType.PROJECT, "Full", "Full desc", "body");
        var withTtl = new MemoryEntry(
                MemoryEntry.MemoryHeader.builder()
                        .name("Full")
                        .description("Full desc")
                        .type(MemoryType.PROJECT)
                        .category("deployment")
                        .importance(8)
                        .source("test")
                        .signature(entry.header().signature())
                        .id(entry.header().id())
                        .ttlDays(30)
                        .build(),
                entry.body());

        String serialized = store.renderMemoryFile(withTtl);
        assertTrue(serialized.contains("category: \"deployment\""),
                "Expected category field in output: " + serialized);
        assertTrue(serialized.contains("importance: 8"),
                "Expected importance field in output: " + serialized);
        assertTrue(serialized.contains("ttl_days: 30"),
                "Expected ttl_days field in output: " + serialized);
    }

    @Test
    void memoryDirShouldReturnConfiguredDir() {
        assertEquals(memoryDir, store.memoryDir());
    }
}
