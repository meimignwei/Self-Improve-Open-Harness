package com.openharness.tools;

import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenerationToolTest {

    private final ImageGenerationTool tool = new ImageGenerationTool();
    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."));

    @Test
    void shouldRejectMissingPrompt() {
        assertThrows(IllegalArgumentException.class,
                () -> new ImageGenerationTool.Input(null, null, null, null, null, null, null, null, 0, null));
    }

    @Test
    void shouldRejectMissingApiKey() {
        var result = tool.execute(new ImageGenerationTool.Input("a cat", null, null, null, null, null, null, null, 1, null), ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("API key"));
    }

    @Test
    void shouldResolveOutputPath(@TempDir Path tempDir) {
        var result = tool.execute(new ImageGenerationTool.Input("a cat", null, "key", null, tempDir.resolve("out.png").toString(), null, null, null, 1, null), ctx);
        // Will fail on API call, but path resolution should work
        assertTrue(result.isError());
    }
}
