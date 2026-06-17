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
    void shouldRejectMissingQueryForWorkspaceSymbol() {
        assertThrows(IllegalArgumentException.class,
                () -> new LspTool.Input("workspace_symbol", null, null, null, null, null));
    }

    @Test
    void shouldRejectMissingFilePathForNonWorkspaceSymbol() {
        assertThrows(IllegalArgumentException.class,
                () -> new LspTool.Input("document_symbol", null, null, null, null, null));
    }

    @Test
    void shouldRejectMissingSymbolOrLineForPositionalOps() {
        // hover requires symbol or line
        assertThrows(IllegalArgumentException.class,
                () -> new LspTool.Input("hover", "f.py", null, null, null, null));
    }

    @Test
    void hoverWithLineShouldNotThrowValidation(@TempDir Path tempDir) throws Exception {
        Path f = tempDir.resolve("test.py");
        Files.writeString(f, "x = 1\n");
        var localCtx = new ToolExecutionContext(tempDir);
        // Input with line provided should not throw validation error
        var input = new LspTool.Input("hover", f.toString(), null, 1, null, null);
        assertNotNull(input);
        // Execute should complete without exception (may fail if pylsp not installed, or succeed with no result)
        var result = tool.execute(input, localCtx);
        assertNotNull(result);
        assertNotNull(result.content());
    }

    @Test
    void shouldRejectMissingFile(@TempDir Path tempDir) {
        var input = new LspTool.Input("document_symbol", tempDir.resolve("missing.py").toString(), "x", 1, null, null);
        var result = tool.execute(input, ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void shouldRejectUnknownOperation(@TempDir Path tempDir) throws Exception {
        Path f = tempDir.resolve("test.py");
        Files.writeString(f, "x = 1\n");
        var input = new LspTool.Input("unknown_op", f.toString(), "x", 1, null, null);
        var result = tool.execute(input, ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("Unknown LSP operation"));
    }

    @Test
    void isReadOnlyShouldReturnTrue() {
        assertTrue(tool.isReadOnly(new LspTool.Input("hover", "f.py", null, 1, 0, null)));
    }

    @Test
    void shouldAcceptWorkspaceSymbolWithQuery() {
        var input = new LspTool.Input("workspace_symbol", null, null, null, null, "my_function");
        assertNotNull(input);
        assertEquals("my_function", input.query());
    }
}
