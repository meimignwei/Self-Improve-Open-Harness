package com.openharness.common;

import com.openharness.common.ToolResult.MediaFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultTest {

    @Test
    void successShouldReturnNonErrorResult() {
        ToolResult r = ToolResult.success("done");
        assertEquals("done", r.content());
        assertFalse(r.isError());
        assertTrue(r.mediaFiles().isEmpty());
    }

    @Test
    void successWithMediaShouldIncludeFiles() {
        var files = List.of(new MediaFile("img.png", "image/png", "base64data"));
        ToolResult r = ToolResult.success("result", files);
        assertEquals(1, r.mediaFiles().size());
        assertEquals("img.png", r.mediaFiles().get(0).fileName());
    }

    @Test
    void errorShouldReturnErrorResult() {
        ToolResult r = ToolResult.error("failed");
        assertEquals("failed", r.content());
        assertTrue(r.isError());
    }

    @Test
    void twoArgConstructorShouldDefaultToEmptyMedia() {
        ToolResult r = new ToolResult("text", false);
        assertTrue(r.mediaFiles().isEmpty());
    }
}
