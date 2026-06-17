package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AskUserQuestionToolTest {

    private final AskUserQuestionTool tool = new AskUserQuestionTool();

    @Test
    void shouldReturnErrorWhenNoAskUserPromptCallback() {
        var ctx = new ToolExecutionContext(Path.of("."));
        ToolResult result = tool.execute(new AskUserQuestionTool.Input("What is your preference?"), ctx);
        assertTrue(result.isError());
        assertTrue(result.content().contains("unavailable"));
    }

    @Test
    void shouldCallAskUserPromptAndReturnAnswer() {
        Function<String, String> prompt = q -> "The answer is 42";
        var metadata = Map.<String, Object>of("ask_user_prompt", prompt);
        var ctx = new ToolExecutionContext(Path.of("."), metadata);
        ToolResult result = tool.execute(new AskUserQuestionTool.Input("What is the answer?"), ctx);
        assertFalse(result.isError());
        assertEquals("The answer is 42", result.content());
    }

    @Test
    void shouldReturnNoResponseWhenAnswerIsEmpty() {
        Function<String, String> prompt = q -> "";
        var metadata = Map.<String, Object>of("ask_user_prompt", prompt);
        var ctx = new ToolExecutionContext(Path.of("."), metadata);
        ToolResult result = tool.execute(new AskUserQuestionTool.Input("Any thoughts?"), ctx);
        assertFalse(result.isError());
        assertEquals("(no response)", result.content());
    }

    @Test
    void shouldStripWhitespaceFromAnswer() {
        Function<String, String> prompt = q -> "  trimmed  ";
        var metadata = Map.<String, Object>of("ask_user_prompt", prompt);
        var ctx = new ToolExecutionContext(Path.of("."), metadata);
        ToolResult result = tool.execute(new AskUserQuestionTool.Input("Question?"), ctx);
        assertEquals("trimmed", result.content());
    }

    @Test
    void shouldRejectNullQuestion() {
        assertThrows(IllegalArgumentException.class,
                () -> new AskUserQuestionTool.Input(null));
    }

    @Test
    void isReadOnlyShouldReturnTrue() {
        assertTrue(tool.isReadOnly(new AskUserQuestionTool.Input("test")));
    }

    @Test
    void nameShouldBeAskUserQuestion() {
        assertEquals("ask_user_question", tool.name());
    }
}
