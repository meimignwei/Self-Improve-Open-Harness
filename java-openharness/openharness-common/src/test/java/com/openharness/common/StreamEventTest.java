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
    void compactProgressEventShouldHoldCounts() {
        var event = new StreamEvent.CompactProgressEvent(5, 32000);
        assertEquals(5, event.removedMessages());
        assertEquals(32000, event.remainingTokens());
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
