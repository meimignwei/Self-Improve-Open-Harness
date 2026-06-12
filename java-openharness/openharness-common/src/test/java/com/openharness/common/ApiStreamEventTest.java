package com.openharness.common;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiStreamEventTest {

    @Test
    void contentDeltaShouldHoldTextAndIndex() {
        var event = new ApiStreamEvent.ContentDelta("partial text", 0);
        assertEquals("partial text", event.text());
        assertEquals(0, event.index());
    }

    @Test
    void toolUseStartShouldHoldIdAndName() {
        var event = new ApiStreamEvent.ToolUseStart("tu_1", "bash");
        assertEquals("tu_1", event.id());
        assertEquals("bash", event.name());
    }

    @Test
    void toolUseInputDeltaShouldHoldIdAndJson() {
        var event = new ApiStreamEvent.ToolUseInputDelta("tu_1", "{\"cmd\":\"ls\"}");
        assertEquals("tu_1", event.id());
        assertEquals("{\"cmd\":\"ls\"}", event.inputJson());
    }

    @Test
    void toolUseCompleteShouldHoldFullInput() {
        var input = JsonNodeFactory.instance.objectNode().put("cmd", "pwd");
        var event = new ApiStreamEvent.ToolUseComplete("tu_1", "bash", input);
        assertEquals("bash", event.name());
        assertEquals("pwd", event.input().get("cmd").asText());
    }

    @Test
    void turnCompleteShouldHoldUsageAndStopReason() {
        var usage = new UsageSnapshot(500, 200);
        var event = new ApiStreamEvent.TurnComplete(usage, "end_turn");
        assertEquals(usage, event.usage());
        assertEquals("end_turn", event.stopReason());
    }

    @Test
    void errorEventShouldHoldCodeAndMessage() {
        var event = new ApiStreamEvent.ErrorEvent("RATE_LIMIT", "too many requests");
        assertEquals("RATE_LIMIT", event.code());
        assertEquals("too many requests", event.message());
    }

    @Test
    void allSubtypesArePermitted() {
        var permitted = ApiStreamEvent.class.getPermittedSubclasses();
        assertNotNull(permitted);
        assertEquals(6, permitted.length);
    }
}
