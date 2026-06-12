package com.openharness.tools;

import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LspToolTest {

    private final LspTool tool = new LspTool();
    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."));

    @Test
    void shouldRejectMissingFilePathForDocumentSymbol() {
        var result = tool.execute(new LspTool.Input("document_symbol", null, null, null, null, null), ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("file_path"));
    }

    @Test
    void shouldRejectMissingFile(@TempDir Path tempDir) {
        var result = tool.execute(new LspTool.Input("document_symbol", tempDir.resolve("missing.py").toString(), null, null, null, null), ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void shouldRejectUnknownOperation(@TempDir Path tempDir) throws Exception {
        Path f = tempDir.resolve("test.py");
        Files.writeString(f, "x = 1\n");
        var result = tool.execute(new LspTool.Input("unknown_op", f.toString(), null, null, null, null), ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown LSP operation"));
    }

    @Test
    void isReadOnlyShouldReturnTrue() {
        assertTrue(tool.isReadOnly(new LspTool.Input("hover", "f.py", null, 1, 0, null)));
    }

    @Test
    void shouldRequireLineForHover(@TempDir Path tempDir) throws Exception {
        Path f = tempDir.resolve("test.py");
        Files.writeString(f, "x = 1\n");
        var localCtx = new ToolExecutionContext(tempDir);
        var result = tool.execute(new LspTool.Input("hover", f.toString(), null, null, null, null), localCtx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("line"));
    }
}
