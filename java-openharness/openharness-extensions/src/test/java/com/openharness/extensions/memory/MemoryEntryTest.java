package com.openharness.extensions.memory;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEntryTest {

    @Test
    void createShouldGenerateIdAndSignature() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "A test memory", "body content");
        assertNotNull(entry.header().id());
        assertEquals("Test", entry.header().name());
        assertEquals("A test memory", entry.header().description());
        assertEquals(MemoryType.USER, entry.header().type());
        assertEquals("body content", entry.body());
        assertNotNull(entry.header().signature());
        assertEquals(2, entry.header().schemaVersion());
        assertEquals(5, entry.header().importance());
        assertFalse(entry.isExpired());
    }

    @Test
    void withUpdatedBodyShouldUpdateSignatureAndTimestamp() throws InterruptedException {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "old body");
        Instant originalUpdatedAt = entry.header().updatedAt();

        Thread.sleep(10);
        var updated = entry.withUpdatedBody("new body");

        assertEquals("new body", updated.body());
        assertEquals(entry.header().id(), updated.header().id());
        assertNotEquals(entry.header().signature(), updated.header().signature());
        assertTrue(updated.header().updatedAt().isAfter(originalUpdatedAt));
    }

    @Test
    void withImportanceShouldClampTo1to10() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var low = entry.withImportance(0);
        assertEquals(1, low.header().importance());

        var high = entry.withImportance(100);
        assertEquals(10, high.header().importance());

        var mid = entry.withImportance(7);
        assertEquals(7, mid.header().importance());
    }

    @Test
    void withDisabledShouldToggleFlag() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        assertFalse(entry.header().disabled());

        var disabled = entry.withDisabled(true);
        assertTrue(disabled.header().disabled());
    }

    @Test
    void isExpiredShouldReturnFalseWhenNoTtl() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        assertNull(entry.header().ttlDays());
        assertFalse(entry.isExpired());
    }

    @Test
    void isExpiredShouldReturnTrueAfterTtl() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var header = entry.header();
        var expiredHeader = new MemoryEntry.MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.category(), header.importance(), header.source(),
                header.signature(), Instant.now().minus(10, ChronoUnit.DAYS), header.updatedAt(),
                1, header.disabled(), header.supersedes());
        var expiredEntry = new MemoryEntry(expiredHeader, entry.body());
        assertTrue(expiredEntry.isExpired());
    }

    @Test
    void isExpiredShouldReturnFalseWithinTtl() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var header = entry.header();
        var validHeader = new MemoryEntry.MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.category(), header.importance(), header.source(),
                header.signature(), Instant.now(), header.updatedAt(),
                30, header.disabled(), header.supersedes());
        var validEntry = new MemoryEntry(validHeader, entry.body());
        assertFalse(validEntry.isExpired());
    }

    @Nested
    class BuilderTest {

        @Test
        void builderShouldCreateValidHeader() {
            var header = MemoryEntry.MemoryHeader.builder()
                    .name("Builder Test")
                    .description("Built from builder")
                    .type(MemoryType.FEEDBACK)
                    .importance(8)
                    .build();

            assertEquals("Builder Test", header.name());
            assertEquals(MemoryType.FEEDBACK, header.type());
            assertEquals(8, header.importance());
            assertNotNull(header.id());
            assertEquals(2, header.schemaVersion());
        }

        @Test
        void builderShouldUseProvidedId() {
            var id = UUID.randomUUID().toString();
            var header = MemoryEntry.MemoryHeader.builder()
                    .name("Test")
                    .type(MemoryType.PROJECT)
                    .id(id)
                    .build();
            assertEquals(id, header.id());
        }

        @Test
        void builderShouldSupportSupersedes() {
            var header = MemoryEntry.MemoryHeader.builder()
                    .name("v2")
                    .type(MemoryType.USER)
                    .supersedes(List.of("old-id-1", "old-id-2"))
                    .build();
            assertEquals(2, header.supersedes().size());
        }

        @Test
        void builderShouldDefaultDisabledToFalse() {
            var header = MemoryEntry.MemoryHeader.builder()
                    .name("Test")
                    .type(MemoryType.USER)
                    .build();
            assertFalse(header.disabled());
        }

        @Test
        void builderDisabledTrueShouldWork() {
            var header = MemoryEntry.MemoryHeader.builder()
                    .name("Test")
                    .type(MemoryType.USER)
                    .disabled(true)
                    .build();
            assertTrue(header.disabled());
        }
    }

    @Test
    void headerDefaultsSchemaVersionTo2() {
        var header = new MemoryEntry.MemoryHeader(0, null, "name", "desc",
                MemoryType.USER, null, 5, null, "sig", Instant.now(), Instant.now(),
                null, false, List.of());
        assertEquals(2, header.schemaVersion());
    }
}
