package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TodoWriteToolTest {

    private final TodoWriteTool tool = new TodoWriteTool();

    @Test
    void shouldAddNewItemToFile(@TempDir Path tempDir) throws IOException {
        Path todoFile = tempDir.resolve("TODO.md");
        Files.writeString(todoFile, "# TODO\n");
        var ctx = new ToolExecutionContext(tempDir);

        ToolResult result = tool.execute(new TodoWriteTool.Input("New task", false, "TODO.md"), ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("Added TODO item"));
        String fileContent = Files.readString(todoFile);
        assertTrue(fileContent.contains("- [ ] New task"));
    }

    @Test
    void shouldCheckExistingUncheckedItem(@TempDir Path tempDir) throws IOException {
        Path todoFile = tempDir.resolve("TODO.md");
        Files.writeString(todoFile, "# TODO\n- [ ] Buy milk\n");
        var ctx = new ToolExecutionContext(tempDir);

        ToolResult result = tool.execute(new TodoWriteTool.Input("Buy milk", true, "TODO.md"), ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("Checked TODO item"));
        String fileContent = Files.readString(todoFile);
        assertTrue(fileContent.contains("- [x] Buy milk"));
        assertFalse(fileContent.contains("- [ ] Buy milk"));
    }

    @Test
    void shouldUncheckExistingCheckedItem(@TempDir Path tempDir) throws IOException {
        Path todoFile = tempDir.resolve("TODO.md");
        Files.writeString(todoFile, "# TODO\n- [x] Done task\n");
        var ctx = new ToolExecutionContext(tempDir);

        ToolResult result = tool.execute(new TodoWriteTool.Input("Done task", false, "TODO.md"), ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("Unchecked TODO item"));
        String fileContent = Files.readString(todoFile);
        assertTrue(fileContent.contains("- [ ] Done task"));
        assertFalse(fileContent.contains("- [x] Done task"));
    }

    @Test
    void shouldReturnNoChangeWhenAlreadyDesiredState(@TempDir Path tempDir) throws IOException {
        Path todoFile = tempDir.resolve("TODO.md");
        Files.writeString(todoFile, "# TODO\n- [x] Completed\n");
        var ctx = new ToolExecutionContext(tempDir);

        ToolResult result = tool.execute(new TodoWriteTool.Input("Completed", true, "TODO.md"), ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("No change needed"));
    }

    @Test
    void shouldCreateFileIfNotExists(@TempDir Path tempDir) {
        var ctx = new ToolExecutionContext(tempDir);
        Path todoFile = tempDir.resolve("TODO.md");
        assertFalse(Files.exists(todoFile));

        ToolResult result = tool.execute(new TodoWriteTool.Input("First item", false, "TODO.md"), ctx);

        assertFalse(result.isError());
        assertTrue(Files.exists(todoFile));
    }

    @Test
    void shouldRejectNullItem() {
        assertThrows(IllegalArgumentException.class,
                () -> new TodoWriteTool.Input(null, false, "TODO.md"));
    }

    @Test
    void isReadOnlyShouldReturnFalse() {
        assertFalse(tool.isReadOnly(new TodoWriteTool.Input("test", false, "TODO.md")));
    }

    @Test
    void nameShouldBeTodoWrite() {
        assertEquals("todo_write", tool.name());
    }

    @Test
    void shouldDefaultPathToTodoMd() {
        var input = new TodoWriteTool.Input("some item", false, null);
        assertEquals("TODO.md", input.path());
    }
}
