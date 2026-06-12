package com.openharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.util.List;

/**
 * Ask the user one or more questions during execution.
 * Java equivalent of Python's ask_user_question tool.
 */
public class AskUserQuestionTool extends BaseTool<AskUserQuestionTool.Input> {

    public AskUserQuestionTool() {
        super("ask_user_question", "Ask the user clarifying questions during execution.", Input.class);
    }

    @Override
    public ToolResult execute(Input args, ToolExecutionContext ctx) {
        if (args.questions() == null || args.questions().isEmpty()) {
            return ToolResult.error("At least one question is required.");
        }

        StringBuilder sb = new StringBuilder("Questions for user:\n\n");
        for (int i = 0; i < args.questions().size(); i++) {
            Question q = args.questions().get(i);
            sb.append(i + 1).append(". ").append(q.question()).append("\n");
            if (q.options() != null) {
                for (int j = 0; j < q.options().size(); j++) {
                    Option opt = q.options().get(j);
                    sb.append("   [").append(j + 1).append("] ").append(opt.label())
                            .append(" — ").append(opt.description()).append("\n");
                }
            }
            sb.append("\n");
        }

        return ToolResult.success(sb.toString());
    }

    @Override public boolean isReadOnly(Input args) { return false; }

    public record Input(List<Question> questions, JsonNode metadata) {
        public Input { if (questions == null) throw new IllegalArgumentException("questions is required"); }
    }

    public record Question(String question, String header, List<Option> options, boolean multiSelect) {}
    public record Option(String label, String description) {}
}
