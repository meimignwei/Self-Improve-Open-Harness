package com.openharness.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMessageTest {

    @Test
    void shouldConstructWithRoleAndContent() {
        var blocks = List.<ContentBlock>of(new ContentBlock.TextBlock("hi"));
        var msg = new ConversationMessage(Role.USER, blocks);
        assertEquals(Role.USER, msg.role());
        assertEquals(1, msg.content().size());
    }

    @Test
    void shouldAllowEmptyContent() {
        var msg = new ConversationMessage(Role.SYSTEM, List.of());
        assertTrue(msg.content().isEmpty());
    }
}
