package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.util.function.Function;

/**
 * Tool for asking the interactive user a follow-up question.
 * Java equivalent of Python's ask_user_question_tool.
 * <p>
 * Checks context.metadata() for an "ask_user_prompt" callback (Function&lt;String, String&gt;).
 * If available, calls it with the question to get user input.
 * If not available, returns an error indicating user interaction is unavailable.
 */
public class AskUserQuestionTool extends BaseTool<AskUserQuestionTool.Input> {

    public AskUserQuestionTool() {
        super("ask_user_question",
                "Ask the interactive user a follow-up question and return the answer.",
                Input.class);
    }

    @Override
    public ToolResult execute(Input args, ToolExecutionContext ctx) {
        Object promptObj = ctx.metadata().get("ask_user_prompt");
        if (promptObj == null) {
            return ToolResult.error("ask_user_question is unavailable in this session");
        }
        if (!(promptObj instanceof Function<?, ?>)) {
            return ToolResult.error("ask_user_question is unavailable in this session");
        }
        @SuppressWarnings("unchecked")
        Function<String, String> prompt = (Function<String, String>) promptObj;
        String answer = prompt.apply(args.question());
        if (answer == null) {
            answer = "";
        }
        answer = answer.strip();
        if (answer.isEmpty()) {
            return ToolResult.success("(no response)");
        }
        return ToolResult.success(answer);
    }

    @Override
    public boolean isReadOnly(Input args) {
        return true;
    }

    /**
     * Input: a single question to ask the interactive user.
     * Python equivalent: AskUserQuestionToolInput — single 'question: str' field.
     */
    public record Input(String question) {
        public Input {
            if (question == null) throw new IllegalArgumentException("question is required");
        }
    }
}
