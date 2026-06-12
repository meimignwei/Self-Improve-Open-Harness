package com.openharness.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFileWriterTest {

    // Simple record for roundtrip testing with OpenHarnessObjectMapper default typing
    record TestData(String name, int count) {}

    @TempDir
    Path tempDir;

    @Test
    void writeJsonShouldCreateFile() {
        Path file = tempDir.resolve("test.json");
        AtomicFileWriter.writeJson(file, new TestData("hello", 42));
        assertTrue(Files.exists(file));
    }

    @Test
    void writeAndReadJsonShouldRoundtrip() {
        Path file = tempDir.resolve("roundtrip.json");
        var record = new TestData("Alice", 30);

        AtomicFileWriter.writeJson(file, record);
        var loaded = AtomicFileWriter.readJson(file, TestData.class);

        assertNotNull(loaded);
        assertEquals("Alice", loaded.name());
        assertEquals(30, loaded.count());
    }

    @Test
    void writeJsonShouldCreateParentDirs() {
        Path file = tempDir.resolve("deep/nested/dir/test.json");
        AtomicFileWriter.writeJson(file, new TestData("data", 1));
        assertTrue(Files.exists(file));
    }

    @Test
    void readJsonShouldReturnNullForMissingFile() {
        Path file = tempDir.resolve("nonexistent.json");
        assertNull(AtomicFileWriter.readJson(file, TestData.class));
    }

    @Test
    void writeJsonShouldNotLeaveTempFiles() throws IOException {
        Path file = tempDir.resolve("clean.json");
        AtomicFileWriter.writeJson(file, new TestData("value", 0));

        try (var stream = Files.list(tempDir)) {
            long tmpCount = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0, tmpCount);
        }
    }

    @Test
    void writeJsonShouldOverwriteExisting() {
        Path file = tempDir.resolve("overwrite.json");
        AtomicFileWriter.writeJson(file, new TestData("v1", 1));
        AtomicFileWriter.writeJson(file, new TestData("v2", 2));

        var loaded = AtomicFileWriter.readJson(file, TestData.class);
        assertEquals("v2", loaded.name());
        assertEquals(2, loaded.count());
    }
}
