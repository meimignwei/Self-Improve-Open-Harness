package com.openharness.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamEventTest {

    @Test
    void assistantTextDeltaShouldHoldText() {
        var event = new StreamEvent.AssistantTextDelta("hello world");
        assertInstanceOf(StreamEvent.class, event);
        assertEquals("hello world", event.text());
    }

    @Test
    void assistantTurnCompleteShouldHoldUsage() {
        var usage = new UsageSnapshot(200, 80);
        var event = new StreamEvent.AssistantTurnComplete(usage);
        assertEquals(usage, event.usage());
    }

    @Test
    void toolStartedShouldHoldNameAndId() {
        var event = new StreamEvent.ToolStarted("read_file", "call_1");
        assertEquals("read_file", event.toolName());
        assertEquals("call_1", event.toolId());
    }

    @Test
    void toolCompletedShouldHoldResult() {
        var result = ToolResult.success("done");
        var event = new StreamEvent.ToolCompleted("write_file", "call_2", result);
        assertEquals("write_file", event.toolName());
        assertFalse(event.result().isError());
    }

    @Test
    void statusEventShouldHoldMessageAndLevel() {
        var event = new StreamEvent.StatusEvent("processing...", StreamEvent.StatusLevel.INFO);
        assertEquals("processing...", event.message());
        assertEquals(StreamEvent.StatusLevel.INFO, event.level());
    }

    @Test
    void compactProgressEventShouldHoldPhaseAndMetadata() {
        var event = new StreamEvent.CompactProgressEvent(
                "compact_start", "auto", "Compacting...", 1, "checkpoint1",
                java.util.Map.of("tokens", 32000));
        assertEquals("compact_start", event.phase());
        assertEquals("auto", event.trigger());
        assertEquals("Compacting...", event.message());
        assertEquals(1, event.attempt());
        assertEquals("checkpoint1", event.checkpoint());
        assertEquals(32000, event.metadata().get("tokens"));
    }

    @Test
    void errorStreamEventShouldHoldMessage() {
        var event = new StreamEvent.ErrorStreamEvent("something went wrong");
        assertEquals("something went wrong", event.message());
    }

    @Test
    void statusLevelShouldHaveThreeValues() {
        assertEquals(3, StreamEvent.StatusLevel.values().length);
    }

    @Test
    void allSubtypesArePermitted() {
        var permitted = StreamEvent.class.getPermittedSubclasses();
        assertNotNull(permitted);
        assertEquals(7, permitted.length);
    }
}
