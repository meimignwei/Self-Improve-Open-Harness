package com.openharness.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JSON-Lines protocol serialization / deserialization.
 */
class JsonLinesProtocolIT {

    @Test
    void readyEventRoundTrip() throws Exception {
        var event = new BackendEvent.ReadyEvent("session-123");
        String json = serializeEvent(event);

        JsonNode tree = OpenHarnessObjectMapper.get().readTree(json);
        assertEquals("ready", tree.get("type").asText());
        assertEquals("session-123", tree.get("sessionId").asText());
    }

    @Test
    void statusEventRoundTrip() throws Exception {
        var event = new BackendEvent.StatusEvent("hello world", "info");
        String json = serializeEvent(event);

        JsonNode tree = OpenHarnessObjectMapper.get().readTree(json);
        assertEquals("status", tree.get("type").asText());
        assertEquals("hello world", tree.get("message").asText());
        assertEquals("info", tree.get("level").asText());
    }

    @Test
    void assistantDeltaEventRoundTrip() throws Exception {
        var event = new BackendEvent.AssistantDeltaEvent("Hello, world!", 0);
        String json = serializeEvent(event);

        JsonNode tree = OpenHarnessObjectMapper.get().readTree(json);
        assertEquals("assistant_delta", tree.get("type").asText());
        assertEquals("Hello, world!", tree.get("text").asText());
        assertEquals(0, tree.get("turnIndex").asInt());
    }

    @Test
    void toolStartedEventRoundTrip() throws Exception {
        var args = OpenHarnessObjectMapper.get().createObjectNode()
                .put("command", "ls")
                .put("timeout", 5000);
        var event = new BackendEvent.ToolStartedEvent("bash", args, "uuid-456");
        String json = serializeEvent(event);

        JsonNode tree = OpenHarnessObjectMapper.get().readTree(json);
        assertEquals("tool_started", tree.get("type").asText());
        assertEquals("bash", tree.get("toolName").asText());
        assertEquals("uuid-456", tree.get("toolUseId").asText());
        assertEquals("ls", tree.get("arguments").get("command").asText());
    }

    @Test
    void toolCompletedEventRoundTrip() throws Exception {
        var result = ToolResult.success("output content");
        var event = new BackendEvent.ToolCompletedEvent("bash", "uuid-456", result);
        String json = serializeEvent(event);

        JsonNode tree = OpenHarnessObjectMapper.get().readTree(json);
        assertEquals("tool_completed", tree.get("type").asText());
        assertEquals("bash", tree.get("toolName").asText());
    }

    @Test
    void errorEventRoundTrip() throws Exception {
        var event = new BackendEvent.ErrorEvent("something failed", "ERR_001", null);
        String json = serializeEvent(event);

        JsonNode tree = OpenHarnessObjectMapper.get().readTree(json);
        assertEquals("error", tree.get("type").asText());
        assertEquals("something failed", tree.get("message").asText());
        assertEquals("ERR_001", tree.get("code").asText());
    }

    @Test
    void shutdownEventRoundTrip() throws Exception {
        var event = new BackendEvent.ShutdownEvent("normal");
        String json = serializeEvent(event);

        JsonNode tree = OpenHarnessObjectMapper.get().readTree(json);
        assertEquals("shutdown", tree.get("type").asText());
        assertEquals("normal", tree.get("reason").asText());
    }

    @Test
    void allEventsSerializable() throws Exception {
        var mapper = OpenHarnessObjectMapper.get();
        var events = List.of(
                new BackendEvent.ReadyEvent("s1"),
                new BackendEvent.StatusEvent("msg", "info"),
                new BackendEvent.AssistantDeltaEvent("text", 0),
                new BackendEvent.ErrorEvent("err", "E1", null),
                new BackendEvent.ShutdownEvent("normal"),
                new BackendEvent.ClearTranscriptEvent(),
                new BackendEvent.PlanModeChangeEvent(true, "plan content")
        );

        for (var event : events) {
            String json = mapper.writeValueAsString(event);
            assertNotNull(json);
            assertFalse(json.isEmpty());

            JsonNode tree = mapper.readTree(json);
            assertTrue(tree.has("type"), "Missing 'type' in: " + json);
        }
    }

    @Test
    void backendOutputProducesValidJsonLines() throws Exception {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true);
        var reader = new BufferedReader(new StringReader(""));
        var output = new BackendOutput(ps, reader, "test-session");

        output.emitReady("test-session");
        output.emitStatus("test status");
        output.emitAssistantDelta("test delta");
        output.emitError("test error");
        output.emitShutdown();

        // Verify each line is valid JSON
        String[] lines = baos.toString().strip().split("\n");
        assertTrue(lines.length >= 5, "Expected >=5 JSON lines, got " + lines.length);

        var mapper = OpenHarnessObjectMapper.get();
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            assertTrue(node.has("type"), "Line has no 'type': " + line);
        }
    }

    @Test
    void frontendRequestDeserialization() throws Exception {
        String userInputJson = "{\"type\":\"user_input\",\"text\":\"hello\"}";
        FrontendRequest req = JsonLinesProtocol.deserialize(userInputJson);
        assertInstanceOf(FrontendRequest.UserInputRequest.class, req);
        assertEquals("hello", ((FrontendRequest.UserInputRequest) req).text());
    }

    private static String serializeEvent(BackendEvent event) throws Exception {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true);
        JsonLinesProtocol.emit(event, ps);
        return baos.toString().strip();
    }
}
