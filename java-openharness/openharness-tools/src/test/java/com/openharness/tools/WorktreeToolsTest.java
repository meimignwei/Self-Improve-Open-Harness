package com.openharness.tools;

import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeToolsTest {

    @Test
    void enterWorktreeRequiresBranch() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorktreeTools.EnterWorktreeTool.Input(null, null, false));
    }

    @Test
    void exitWorktreeDoesNotRequirePath() {
        var input = new WorktreeTools.ExitWorktreeTool.Input(null, false);
        assertNotNull(input);
    }

    @Test
    void enterWorktreeToolNameShouldBeCorrect() {
        var tool = new WorktreeTools.EnterWorktreeTool();
        assertEquals("enter_worktree", tool.name());
    }

    @Test
    void exitWorktreeToolNameShouldBeCorrect() {
        var tool = new WorktreeTools.ExitWorktreeTool();
        assertEquals("exit_worktree", tool.name());
    }
}
