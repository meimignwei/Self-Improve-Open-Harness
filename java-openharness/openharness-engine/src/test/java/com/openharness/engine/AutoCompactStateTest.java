package com.openharness.engine;

import com.openharness.common.ContentBlock;
import com.openharness.common.ConversationMessage;
import com.openharness.common.Role;
import com.openharness.common.UsageSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoCompactStateTest {

    @Test
    void shouldNotCompactBelowThreshold() {
        AutoCompactState state = new AutoCompactState(1000);
        assertFalse(state.shouldCompact(new UsageSnapshot(100, 100)));
    }

    @Test
    void shouldCompactAboveThreshold() {
        AutoCompactState state = new AutoCompactState(100);
        assertTrue(state.shouldCompact(new UsageSnapshot(50, 60)));
    }

    @Test
    void shouldNotCompactWhenThresholdIsZero() {
        AutoCompactState state = new AutoCompactState(0);
        assertFalse(state.shouldCompact(new UsageSnapshot(1000, 1000)));
    }

    @Test
    void shouldTruncateToolResultBlocks() {
        AutoCompactState state = new AutoCompactState(100);
        state.shouldCompact(new UsageSnapshot(200, 0));

        String longContent = "a".repeat(40000);
        var msg = new ConversationMessage(Role.ASSISTANT, List.of(
                new ContentBlock.ToolResultBlock("id-1", longContent, false)
        ));

        List<ConversationMessage> compacted = state.compact(List.of(msg));
        ContentBlock.ToolResultBlock trb = (ContentBlock.ToolResultBlock) compacted.get(0).content().get(0);
        assertTrue(trb.content().length() < longContent.length());
    }

    @Test
    void shouldResetAccumulatedTokensAfterCompact() {
        AutoCompactState state = new AutoCompactState(100);
        state.shouldCompact(new UsageSnapshot(200, 0));
        var msg = new ConversationMessage(Role.ASSISTANT, List.of(new ContentBlock.TextBlock("x")));
        state.compact(List.of(msg));
        assertEquals(0, state.accumulatedTokens());
    }

    @Test
    void shouldHandleNullUsage() {
        AutoCompactState state = new AutoCompactState(100);
        assertFalse(state.shouldCompact(null));
    }

    @Test
    void shouldPreserveNonToolBlocks() {
        AutoCompactState state = new AutoCompactState(100);
        state.shouldCompact(new UsageSnapshot(200, 0));

        var msg = new ConversationMessage(Role.ASSISTANT, List.of(
                new ContentBlock.TextBlock("hello world")
        ));

        List<ConversationMessage> compacted = state.compact(List.of(msg));
        assertEquals("hello world", ((ContentBlock.TextBlock) compacted.get(0).content().get(0)).text());
    }
}
