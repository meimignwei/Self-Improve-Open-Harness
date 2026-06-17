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
        assertTrue(entry.header().id().startsWith("mem-"));
        assertEquals("Test", entry.header().name());
        assertEquals("A test memory", entry.header().description());
        assertEquals(MemoryType.USER, entry.header().type());
        assertEquals("body content", entry.body());
        assertNotNull(entry.header().signature());
        assertEquals(1, entry.header().schemaVersion());
        assertEquals(5, entry.header().importance());
        assertFalse(entry.header().isExpired());
    }

    @Test
    void withUpdatedBodyShouldUpdateSignatureAndTimestamp() throws InterruptedException {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "old body");
        Instant originalUpdatedAt = entry.header().updatedAt();

        Thread.sleep(2000); // Must cross second boundary since Instant is truncated to seconds
        var updated = entry.withUpdatedBody("new body");

        assertEquals("new body", updated.body());
        assertEquals(entry.header().id(), updated.header().id());
        assertNotEquals(entry.header().signature(), updated.header().signature());
        assertTrue(updated.header().updatedAt().isAfter(originalUpdatedAt));
    }

    @Test
    void withImportanceShouldUpdateValue() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var low = entry.withImportance(0);
        assertEquals(0, low.header().importance());

        var high = entry.withImportance(100);
        assertEquals(100, high.header().importance());

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
        assertFalse(entry.header().isExpired());
    }

    @Test
    void isExpiredShouldReturnTrueAfterTtl() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var header = entry.header();
        Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS);
        var expiredHeader = new MemoryEntry.MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.scope(), header.category(), header.importance(), header.source(),
                header.signature(), tenDaysAgo, tenDaysAgo,
                1, header.disabled(), header.supersedes(), header.tags());
        var expiredEntry = new MemoryEntry(expiredHeader, entry.body());
        assertTrue(expiredEntry.header().isExpired());
    }

    @Test
    void isExpiredShouldReturnFalseWithinTtl() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var header = entry.header();
        var validHeader = new MemoryEntry.MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.scope(), header.category(), header.importance(), header.source(),
                header.signature(), Instant.now(), header.updatedAt(),
                30, header.disabled(), header.supersedes(), header.tags());
        var validEntry = new MemoryEntry(validHeader, entry.body());
        assertFalse(validEntry.header().isExpired());
    }

    @Test
    void isExpiredUsesUpdatedAtBeforeCreatedAt() {
        var header = new MemoryEntry.MemoryHeader(
                1, MemoryEntry.generateMemoryId(), "Test", "desc",
                MemoryType.USER, "project", "knowledge", 5, null,
                "sig", Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS),
                5, false, List.of(), List.of());
        var entry = new MemoryEntry(header, "body");
        // updatedAt is 1 day ago, TTL is 5 days → not expired
        assertFalse(entry.header().isExpired());

        var expiredHeader = new MemoryEntry.MemoryHeader(
                1, MemoryEntry.generateMemoryId(), "Test2", "desc",
                MemoryType.USER, "project", "knowledge", 5, null,
                "sig", Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().minus(10, ChronoUnit.DAYS),
                5, false, List.of(), List.of());
        var expiredEntry = new MemoryEntry(expiredHeader, "body");
        // updatedAt is 10 days ago, TTL is 5 days → expired
        assertTrue(expiredEntry.header().isExpired());
    }

    @Test
    void idFormatShouldMatchPythonGenerateMemoryId() {
        String id = MemoryEntry.generateMemoryId();
        assertTrue(id.startsWith("mem-"), "ID should start with 'mem-': " + id);
        // Format: mem-YYYYMMDD-HHmmss-<8hex>
        assertTrue(id.matches("mem-\\d{8}-\\d{6}-[0-9a-f]{8}"),
                "ID format should be mem-YYYYMMDD-HHmmss-8hex: " + id);
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
            assertEquals(1, header.schemaVersion());
            assertEquals("project", header.scope());
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
    void headerDefaultsSchemaVersionTo1() {
        var header = new MemoryEntry.MemoryHeader(0, null, "name", "desc",
                MemoryType.USER, null, "knowledge", 5, null, "sig",
                Instant.now(), Instant.now(), null, false, List.of(), List.of());
        assertEquals(1, header.schemaVersion());
    }

    @Test
    void computeSignatureAlignsWithPython() {
        // Same content + type + category should produce identical signatures
        String sig1 = MemoryEntry.computeSignature("body content", "user", "knowledge");
        String sig2 = MemoryEntry.computeSignature("body content", "user", "knowledge");
        assertEquals(sig1, sig2);

        // Different content should produce different signatures
        String sig3 = MemoryEntry.computeSignature("different body", "user", "knowledge");
        assertNotEquals(sig1, sig3);

        // Normalization: whitespace and case should be normalized
        String sig4 = MemoryEntry.computeSignature("  BODY   CONTENT  ", "USER", "KNOWLEDGE");
        assertEquals(sig1, sig4);
    }
}
