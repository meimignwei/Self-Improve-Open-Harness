package com.openharness.tools;

import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImageToTextToolTest {

    private final ImageToTextTool tool = new ImageToTextTool();
    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."));

    @Test
    void shouldRejectMissingImageDataAndPath() {
        var result = tool.execute(new ImageToTextTool.Input(null, null, null, null, 100), ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("image_data") || result.content().contains("image_path"));
    }

    @Test
    void shouldRejectMissingVisionConfig() {
        String b64 = Base64.getEncoder().encodeToString(new byte[]{0, 1, 2});
        var result = tool.execute(new ImageToTextTool.Input(b64, null, null, "image/png", 100), ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("not configured"));
    }

    @Test
    void shouldReadImageFromPath(@TempDir Path tempDir) throws Exception {
        Path img = tempDir.resolve("test.png");
        Files.write(img, new byte[]{0, 1, 2});
        var result = tool.execute(new ImageToTextTool.Input(null, img.toString(), null, null, 100), ctx);
        // Will fail on missing vision config
        assertTrue(result.isError());
    }

    @Test
    void isReadOnlyShouldReturnTrue() {
        assertTrue(tool.isReadOnly(new ImageToTextTool.Input(null, null, null, null, 100)));
    }
}
