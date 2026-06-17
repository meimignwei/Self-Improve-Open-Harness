package com.openharness.tools;

import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenerationToolTest {

    private final ImageGenerationTool tool = new ImageGenerationTool();

    @Test
    void shouldDefaultPromptWhenNull() {
        // prompt defaults to DEFAULT_PROMPT when null or blank
        var input = new ImageGenerationTool.Input(null, null, null, null, null, null, null, 1, null, null, null, null, null, null, null, false);
        assertNotNull(input.prompt());
        assertFalse(input.prompt().isBlank());
    }

    @Test
    void shouldDefaultValuesMatchPython() {
        var input = new ImageGenerationTool.Input("a cat", null, null, null, null, null, null, 1, null, null, null, null, null, null, null, false);
        assertEquals("auto", input.size());
        assertEquals("medium", input.quality());
        assertEquals("png", input.outputFormat());
        assertEquals(1, input.n());
    }

    @Test
    void shouldClampNToValidRange() {
        var input = new ImageGenerationTool.Input("test", null, null, null, null, null, null, 0, null, null, null, null, null, null, null, false);
        assertEquals(1, input.n()); // clamped from 0 to 1
        input = new ImageGenerationTool.Input("test", null, null, null, null, null, null, 20, null, null, null, null, null, null, null, false);
        assertEquals(10, input.n()); // clamped from 20 to 10
    }

    @Test
    void shouldRejectMissingConfig() {
        var ctx = new ToolExecutionContext(Path.of("."), Map.of());
        var input = new ImageGenerationTool.Input("a cat", null, null, null, null, null, null, 1, null, null, null, null, null, null, null, false);
        var result = tool.execute(input, ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("API key"));
    }

    @Test
    void shouldReadConfigFromMetadata() {
        var ctx = new ToolExecutionContext(Path.of("."),
                Map.of("image_generation_config", Map.of("api_key", "test-key")));
        // Will fail on actual API call, but should not fail on missing config
        var input = new ImageGenerationTool.Input("a cat", null, null, null, null, null, null, 1, null, null, null, null, null, null, null, false);
        var result = tool.execute(input, ctx);
        assertTrue(result.isError()); // Fails on API call
        assertFalse(result.content().contains("API key is not configured")); // Config was picked up
    }

    @Test
    void isReadOnlyShouldReturnFalse() {
        assertFalse(tool.isReadOnly(new ImageGenerationTool.Input("test", null, null, null, null, null, null, 1, null, null, null, null, null, null, null, false)));
    }
}
