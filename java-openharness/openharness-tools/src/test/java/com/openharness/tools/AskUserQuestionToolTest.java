package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.tools.AskUserQuestionTool.Option;
import com.openharness.tools.AskUserQuestionTool.Question;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AskUserQuestionToolTest {

    private final AskUserQuestionTool tool = new AskUserQuestionTool();
    private final ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."));

    @Test
    void shouldFormatSingleQuestion() {
        var q = new Question("What is your preference?", "Preference",
                List.of(new Option("Option A", "First choice"), new Option("Option B", "Second choice")),
                false);
        ToolResult result = tool.execute(new AskUserQuestionTool.Input(List.of(q), null), ctx);
        assertFalse(result.isError());
        assertTrue(result.content().contains("What is your preference?"));
        assertTrue(result.content().contains("[1] Option A"));
        assertTrue(result.content().contains("[2] Option B"));
    }

    @Test
    void shouldHandleQuestionWithoutOptions() {
        var q = new Question("What do you think?", null, null, false);
        ToolResult result = tool.execute(new AskUserQuestionTool.Input(List.of(q), null), ctx);
        assertFalse(result.isError());
        assertTrue(result.content().contains("What do you think?"));
    }

    @Test
    void shouldHandleMultipleQuestions() {
        var q1 = new Question("Q1?", "H1", null, false);
        var q2 = new Question("Q2?", "H2", null, false);
        ToolResult result = tool.execute(new AskUserQuestionTool.Input(List.of(q1, q2), null), ctx);
        assertTrue(result.content().contains("1. Q1?"));
        assertTrue(result.content().contains("2. Q2?"));
    }

    @Test
    void shouldRejectEmptyQuestions() {
        ToolResult result = tool.execute(new AskUserQuestionTool.Input(List.of(), null), ctx);
        assertTrue(result.isError());
    }

    @Test
    void shouldRejectNullQuestions() {
        assertThrows(IllegalArgumentException.class,
                () -> new AskUserQuestionTool.Input(null, null));
    }

    @Test
    void nameShouldBeAskUserQuestion() {
        assertEquals("ask_user_question", tool.name());
    }
}
