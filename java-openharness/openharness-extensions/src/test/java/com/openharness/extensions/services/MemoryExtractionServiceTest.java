package com.openharness.extensions.services;

import com.openharness.common.ContentBlock;
import com.openharness.common.ConversationMessage;
import com.openharness.common.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MemoryExtractionServiceTest {

    private MemoryExtractionService service;
    private Path cwd;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        service = new MemoryExtractionService();
        cwd = tempDir;
    }

    // ── extractJsonObject ──

    @Test
    void extractJsonObjectShouldReturnJsonWhenWrapped() {
        String result = MemoryExtractionService.extractJsonObject(
                "some text {\"memories\":[]} more text");
        assertEquals("{\"memories\":[]}", result);
    }

    @Test
    void extractJsonObjectShouldReturnStrippedWhenAlreadyJson() {
        String result = MemoryExtractionService.extractJsonObject(
                "  {\"memories\":[{\"title\":\"t\",\"body\":\"b\"}]}  ");
        assertEquals("{\"memories\":[{\"title\":\"t\",\"body\":\"b\"}]}", result);
    }

    @Test
    void extractJsonObjectShouldReturnOriginalWhenNoBraces() {
        String result = MemoryExtractionService.extractJsonObject("plain text");
        assertEquals("plain text", result);
    }

    // ── summarizeMessage ──

    @Test
    void summarizeMessageShouldCollapseWhitespace() {
        var msg = new ConversationMessage(Role.USER, List.of(
                new ContentBlock.TextBlock("hello   world\nfrom   java")));
        String result = MemoryExtractionService.summarizeMessage(msg);
        assertEquals("user: hello world from java", result);
    }

    @Test
    void summarizeMessageShouldShowToolCalls() {
        var msg = new ConversationMessage(Role.ASSISTANT, List.of(
                new ContentBlock.ToolUseBlock("id1", "write_file",
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                .put("path", "test.md"))));
        String result = MemoryExtractionService.summarizeMessage(msg);
        assertEquals("assistant: tool calls -> write_file", result);
    }

    @Test
    void summarizeMessageShouldHandleNonTextContent() {
        var msg = new ConversationMessage(Role.USER, List.of(
                new ContentBlock.ImageBlock("image/png", "base64data")));
        String result = MemoryExtractionService.summarizeMessage(msg);
        assertEquals("user: [non-text content]", result);
    }

    @Test
    void summarizeMessageShouldTruncateLongText() {
        String longText = "a".repeat(1500);
        var msg = new ConversationMessage(Role.USER, List.of(
                new ContentBlock.TextBlock(longText)));
        String result = MemoryExtractionService.summarizeMessage(msg);
        assertTrue(result.length() <= 1200 + 6); // "user: " + 1200 chars
    }

    // ── isReadOnlyShell ──

    @Test
    void isReadOnlyShellShouldAllowSafeCommands() {
        assertTrue(MemoryExtractionService.isReadOnlyShell("ls"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("pwd"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("cat file.txt"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("grep pattern file"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("git status"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("find . -name '*.java'"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("head -n 10 file"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("tail -f log"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("wc -l file"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("sed 's/a/b/' file"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("awk '{print $1}' file"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("stat file"));
        assertTrue(MemoryExtractionService.isReadOnlyShell("rg pattern"));
    }

    @Test
    void isReadOnlyShellShouldDenyDangerousCommands() {
        assertFalse(MemoryExtractionService.isReadOnlyShell("rm file.txt"));
        assertFalse(MemoryExtractionService.isReadOnlyShell("mv a b"));
        assertFalse(MemoryExtractionService.isReadOnlyShell("cp a b"));
        assertFalse(MemoryExtractionService.isReadOnlyShell("echo hi > out.txt"));
        assertFalse(MemoryExtractionService.isReadOnlyShell("echo hi >> out.txt"));
        assertFalse(MemoryExtractionService.isReadOnlyShell("sed -i 's/a/b/' file"));
        assertFalse(MemoryExtractionService.isReadOnlyShell("tee file"));
        assertFalse(MemoryExtractionService.isReadOnlyShell("python -c 'print(1)'"));
        assertFalse(MemoryExtractionService.isReadOnlyShell("python3 -c 'print(1)'"));
    }

    @Test
    void isReadOnlyShellShouldDenyEmptyCommand() {
        assertFalse(MemoryExtractionService.isReadOnlyShell(""));
        assertFalse(MemoryExtractionService.isReadOnlyShell("   "));
    }

    @Test
    void isReadOnlyShellShouldDenyUnknownCommands() {
        assertFalse(MemoryExtractionService.isReadOnlyShell("npm install"));
        assertFalse(MemoryExtractionService.isReadOnlyShell("curl http://example.com"));
    }

    // ── parseExtractionRecords ──

    @Test
    void parseExtractionRecordsShouldParseValidJson() {
        String json = """
                {"memories": [
                    {"title": "Test", "body": "Content", "type": "project", "scope": "project"}
                ]}""";
        var records = service.parseExtractionRecords(json, 3);
        assertEquals(1, records.size());
        assertEquals("Test", records.get(0).title());
        assertEquals("Content", records.get(0).body());
    }

    @Test
    void parseExtractionRecordsShouldRespectMaxRecords() {
        String json = """
                {"memories": [
                    {"title": "A", "body": "a"},
                    {"title": "B", "body": "b"},
                    {"title": "C", "body": "c"}
                ]}""";
        var records = service.parseExtractionRecords(json, 2);
        assertEquals(2, records.size());
    }

    @Test
    void parseExtractionRecordsShouldSkipMissingTitleOrBody() {
        String json = """
                {"memories": [
                    {"title": "", "body": "b"},
                    {"title": "T", "body": ""},
                    {"title": "Valid", "body": "Content"}
                ]}""";
        var records = service.parseExtractionRecords(json, 5);
        assertEquals(1, records.size());
        assertEquals("Valid", records.get(0).title());
    }

    @Test
    void parseExtractionRecordsShouldReturnEmptyForInvalidJson() {
        var records = service.parseExtractionRecords("not json", 3);
        assertTrue(records.isEmpty());
    }

    @Test
    void parseExtractionRecordsShouldReturnEmptyWhenNoMemoriesKey() {
        var records = service.parseExtractionRecords("{\"other\":[]}", 3);
        assertTrue(records.isEmpty());
    }

    @Test
    void parseExtractionRecordsShouldParseTags() {
        String json = """
                {"memories": [
                    {"title": "T", "body": "B", "tags": ["java", "memory"]}
                ]}""";
        var records = service.parseExtractionRecords(json, 3);
        assertEquals(1, records.size());
        assertEquals(List.of("java", "memory"), records.get(0).tags());
    }

    @Test
    void parseExtractionRecordsShouldDefaultTypeAndScope() {
        String json = """
                {"memories": [
                    {"title": "T", "body": "B"}
                ]}""";
        var records = service.parseExtractionRecords(json, 3);
        assertEquals(1, records.size());
        assertEquals("project", records.get(0).scope());
    }

    // ── validateExtractionToolRequest ──

    @Test
    void validateExtractionToolRequestShouldAllowReadTools(@TempDir Path memoryDir) {
        var result = MemoryExtractionService.validateExtractionToolRequest(
                "read_file", Map.of(), memoryDir);
        assertTrue(result.getKey());
    }

    @Test
    void validateExtractionToolRequestShouldAllowReadOnlyBash(@TempDir Path memoryDir) {
        var result = MemoryExtractionService.validateExtractionToolRequest(
                "bash", Map.of("command", "ls -la"), memoryDir);
        assertTrue(result.getKey());
    }

    @Test
    void validateExtractionToolRequestShouldDenyDangerousBash(@TempDir Path memoryDir) {
        var result = MemoryExtractionService.validateExtractionToolRequest(
                "bash", Map.of("command", "rm -rf /"), memoryDir);
        assertFalse(result.getKey());
        assertTrue(result.getValue().contains("read-only"));
    }

    @Test
    void validateExtractionToolRequestShouldAllowWriteWithinMemoryDir(@TempDir Path memoryDir) {
        var result = MemoryExtractionService.validateExtractionToolRequest(
                "write_file", Map.of("path", memoryDir.resolve("test.md").toString()), memoryDir);
        assertTrue(result.getKey());
    }

    @Test
    void validateExtractionToolRequestShouldDenyWriteOutsideMemoryDir(@TempDir Path memoryDir) {
        var result = MemoryExtractionService.validateExtractionToolRequest(
                "write_file", Map.of("path", "/etc/passwd"), memoryDir);
        assertFalse(result.getKey());
        assertTrue(result.getValue().contains("must stay within"));
    }

    @Test
    void validateExtractionToolRequestShouldDenyUnknownTools(@TempDir Path memoryDir) {
        var result = MemoryExtractionService.validateExtractionToolRequest(
                "delete_file", Map.of(), memoryDir);
        assertFalse(result.getKey());
        assertTrue(result.getValue().contains("cannot use tool"));
    }

    // ── ExtractionRecord defaults ──

    @Test
    void extractionRecordShouldDefaultMissingFields() {
        var record = new MemoryExtractionService.ExtractionRecord(
                "title", "body", null, null, null, null);
        assertEquals("project", record.scope());
        assertNotNull(record.memoryType());
        assertEquals("", record.description());
        assertEquals(List.of(), record.tags());
    }

    // ── ExtractionResult defaults ──

    @Test
    void extractionResultShouldDefaultNullFields() {
        var result = new MemoryExtractionService.ExtractionResult(
                true, null, null, null);
        assertEquals("", result.reason());
        assertEquals(List.of(), result.records());
        assertEquals(List.of(), result.writtenPaths());
    }
}
