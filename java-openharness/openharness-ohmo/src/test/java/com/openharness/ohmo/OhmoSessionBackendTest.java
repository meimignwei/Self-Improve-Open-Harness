package com.openharness.ohmo;

import com.openharness.common.UsageSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OhmoSessionBackendTest {

    @TempDir
    Path tempDir;

    private Path workspaceRoot;
    private OhmoSessionBackend backend;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspace");
        backend = new OhmoSessionBackend(workspaceRoot);
    }

    @Test
    void saveSnapshotShouldCreateFile() {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "text", "Hello"));

        Path path = backend.saveSnapshot("/tmp", "claude-sonnet-4-6",
                "You are helpful", messages, new UsageSnapshot(100, 50),
                "sess-001", "key-abc", Map.of());

        assertNotNull(path);
        assertTrue(Files.exists(path));
    }

    @Test
    void listSnapshotsShouldReturnSavedSnapshots() {
        List<Object> messages = List.of(Map.of("role", "user", "text", "Hi"));

        backend.saveSnapshot("/tmp", "claude", "system", messages,
                new UsageSnapshot(10, 5), "s1", "key-1", Map.of());
        backend.saveSnapshot("/tmp", "claude", "system", messages,
                new UsageSnapshot(20, 10), "s2", "key-2", Map.of());

        var snapshots = backend.listSnapshots(10);
        assertTrue(snapshots.size() >= 2);
    }

    @Test
    void loadLatestShouldReturnMostRecent() throws Exception {
        List<Object> messages1 = List.of(Map.of("role", "user", "text", "First"));
        List<Object> messages2 = List.of(Map.of("role", "user", "text", "Second"));

        backend.saveSnapshot("/tmp", "claude", "sys", messages1,
                new UsageSnapshot(10, 5), "s1", "shared-key", Map.of());
        Thread.sleep(50);
        backend.saveSnapshot("/tmp", "claude", "sys", messages2,
                new UsageSnapshot(20, 10), "s2", "shared-key", Map.of());

        var latest = backend.loadLatest();
        assertNotNull(latest);
        assertEquals("s2", latest.get("session_id"));
    }

    @Test
    void loadLatestForSessionKeyShouldReturnLatestForThatKey() throws Exception {
        List<Object> messages = List.of(Map.of("role", "user", "text", "Hi"));

        backend.saveSnapshot("/tmp", "claude", "sys", messages,
                new UsageSnapshot(10, 5), "s1", "key-alpha", Map.of());
        backend.saveSnapshot("/tmp", "claude", "sys", messages,
                new UsageSnapshot(20, 10), "s2", "key-beta", Map.of());

        var result = backend.loadLatestForSessionKey("key-alpha");
        assertNotNull(result);
    }

    @Test
    void loadBySessionIdShouldReturnCorrectSession() {
        List<Object> messages = List.of(Map.of("role", "user", "text", "Target"));

        backend.saveSnapshot("/tmp", "claude", "sys", messages,
                new UsageSnapshot(10, 5), "target-session", "key-x", Map.of());

        var result = backend.loadBySessionId("target-session");
        assertNotNull(result);
        assertEquals("target-session", result.get("session_id"));
    }

    @Test
    void exportMarkdownShouldCreateTranscript() throws Exception {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "text", "What is AI?"));
        messages.add(Map.of("role", "assistant", "text", "AI stands for..."));

        Path markdown = backend.exportMarkdown(messages);
        assertNotNull(markdown);
        String content = Files.readString(markdown);
        assertTrue(content.contains("# ohmo Session Transcript"));
        assertTrue(content.contains("What is AI?"));
    }

    @Test
    void saveShouldNotThrow() {
        List<Object> messages = List.of(Map.of("role", "user", "text", "Test"));
        assertDoesNotThrow(() -> backend.save("my-key", "sess-1", messages, Map.of()));
    }

    @Test
    void deleteShouldNotThrow() {
        assertDoesNotThrow(() -> backend.delete("nonexistent-key"));
    }

    @Test
    void loadLatestShouldReturnNullForEmptyDir() {
        var result = backend.loadLatest();
        assertNull(result);
    }
}
