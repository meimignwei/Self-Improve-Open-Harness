package com.openharness.engine;

import com.openharness.common.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolCarryoverTest {

    @Test
    void shouldNotCarryoverShortContent() {
        ToolCarryover carryover = new ToolCarryover(Path.of("/dev/null"));
        carryover.evaluate("grep", ToolResult.success("short"));
        assertEquals("", carryover.buildPromptSnippet());
    }

    @Test
    void shouldNotCarryoverErrors() {
        ToolCarryover carryover = new ToolCarryover(Path.of("/dev/null"));
        carryover.evaluate("grep", ToolResult.error("something went wrong".repeat(50)));
        assertEquals("", carryover.buildPromptSnippet());
    }

    @Test
    void shouldCarryoverLongReadContent(@TempDir Path tempDir) {
        Path store = tempDir.resolve("carryover.json");
        ToolCarryover carryover = new ToolCarryover(store);
        String longContent = "line\n".repeat(100);
        carryover.evaluate("read", ToolResult.success(longContent));

        String snippet = carryover.buildPromptSnippet();
        assertTrue(snippet.contains("Carried-over context"));
        assertTrue(snippet.contains("read"));
    }

    @Test
    void shouldPersistToDisk(@TempDir Path tempDir) {
        Path store = tempDir.resolve("carryover.json");
        ToolCarryover carryover = new ToolCarryover(store);
        carryover.evaluate("grep", ToolResult.success("result\n".repeat(100)));

        assertTrue(Files.exists(store));

        ToolCarryover reload = new ToolCarryover(store);
        assertTrue(reload.buildPromptSnippet().contains("grep"));
    }

    @Test
    void shouldClearItems(@TempDir Path tempDir) {
        Path store = tempDir.resolve("carryover.json");
        ToolCarryover carryover = new ToolCarryover(store);
        carryover.evaluate("read", ToolResult.success("content\n".repeat(100)));
        carryover.clear();
        assertEquals("", carryover.buildPromptSnippet());
    }

    @Test
    void shouldReplaceExistingToolEntry(@TempDir Path tempDir) {
        Path store = tempDir.resolve("carryover.json");
        ToolCarryover carryover = new ToolCarryover(store);
        carryover.evaluate("read", ToolResult.success("first\n".repeat(100)));
        carryover.evaluate("read", ToolResult.success("second\n".repeat(100)));

        String snippet = carryover.buildPromptSnippet();
        assertTrue(snippet.contains("second"));
    }
}
