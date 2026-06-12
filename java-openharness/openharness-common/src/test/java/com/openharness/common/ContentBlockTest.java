package com.openharness.common;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentBlockTest {

    @Test
    void textBlockShouldImplementContentBlock() {
        var block = new ContentBlock.TextBlock("hello");
        assertInstanceOf(ContentBlock.class, block);
        assertEquals("hello", block.text());
    }

    @Test
    void imageBlockShouldHoldMediaTypeAndData() {
        var block = new ContentBlock.ImageBlock("image/png", "abc123");
        assertEquals("image/png", block.mediaType());
        assertEquals("abc123", block.base64Data());
    }

    @Test
    void toolUseBlockShouldHoldIdNameAndInput() {
        var input = JsonNodeFactory.instance.objectNode().put("key", "val");
        var block = new ContentBlock.ToolUseBlock("tid1", "my_tool", input);
        assertEquals("tid1", block.id());
        assertEquals("my_tool", block.name());
        assertEquals("val", block.input().get("key").asText());
    }

    @Test
    void toolResultBlockShouldHoldIdContentAndErrorFlag() {
        var block = new ContentBlock.ToolResultBlock("tid1", "result text", true);
        assertEquals("tid1", block.toolUseId());
        assertEquals("result text", block.content());
        assertTrue(block.isError());
    }

    @Test
    void allSubtypesArePermitted() {
        var permitted = ContentBlock.class.getPermittedSubclasses();
        assertNotNull(permitted);
        assertEquals(4, permitted.length);
    }
}
