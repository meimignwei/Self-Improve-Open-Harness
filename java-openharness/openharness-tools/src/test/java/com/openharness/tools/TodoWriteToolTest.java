package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.tools.TodoWriteTool.TodoItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoWriteToolTest {

    private final TodoWriteTool tool = new TodoWriteTool();
    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."));

    @Test
    void shouldFormatTaskList() {
        var items = List.of(
                new TodoItem("Task 1", "First task", "completed", 1),
                new TodoItem("Task 2", "Second task", "pending", 2)
        );
        ToolResult result = tool.execute(new TodoWriteTool.Input(items), ctx);
        assertFalse(result.isError());
        assertTrue(result.content().contains("[x] **Task 1**"));
        assertTrue(result.content().contains("[ ] **Task 2**"));
        assertTrue(result.content().contains("1/2 tasks completed"));
    }

    @Test
    void shouldRejectEmptyTodos() {
        ToolResult result = tool.execute(new TodoWriteTool.Input(List.of()), ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("At least one todo"));
    }

    @Test
    void shouldRejectNullTodos() {
        assertThrows(IllegalArgumentException.class,
                () -> new TodoWriteTool.Input(null));
    }

    @Test
    void todoItemShouldDefaultStatusToPending() {
        var item = new TodoItem("Subject only", null, null, 0);
        assertEquals("pending", item.status());
    }

    @Test
    void allCompletedShouldShowFullProgress() {
        var items = List.of(
                new TodoItem("A", "", "completed", 1),
                new TodoItem("B", "", "completed", 1)
        );
        ToolResult result = tool.execute(new TodoWriteTool.Input(items), ctx);
        assertTrue(result.content().contains("2/2 tasks completed"));
    }

    @Test
    void isReadOnlyShouldReturnFalse() {
        assertFalse(tool.isReadOnly(new TodoWriteTool.Input(List.of(new TodoItem("x", null, null, 0)))));
    }

    @Test
    void nameShouldBeTodoWrite() {
        assertEquals("todo_write", tool.name());
    }
}
