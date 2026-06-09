package io.openharness.unit;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.openharness.core.tools.AskUserQuestionTool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AskUserQuestionToolTest {

    private static String textOf(ToolResultBlock r) {
        return ((TextBlock) r.getOutput().get(0)).getText();
    }

    @Test
    void shouldReturnAnswerForSingleSelectQuestion() {
        String questionsJson = """
                [{"question":"Which framework?","header":"FW","options":[{"label":"Spring","description":"Java standard"}],"multiSelect":false}]""";
        String answerJson = """
                {"FW":"Spring"}""";

        AskUserQuestionTool tool = new AskUserQuestionTool();
        tool.setUserInputProvider((json, timeout) -> answerJson);

        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("id", "ask_user_question", Map.of("questions", questionsJson)))
                .input(Map.of("questions", questionsJson))
                .build();
        ToolResultBlock result = tool.callAsync(param).block();

        assertThat(textOf(result)).contains("Spring");
    }

    @Test
    void shouldHandleMultiSelect() {
        String questionsJson = """
                [{"question":"Which features?","header":"Features","options":[{"label":"A","description":"..."},{"label":"B","description":"..."}],"multiSelect":true}]""";
        String answerJson = """
                {"Features":["A","B"]}""";

        AskUserQuestionTool tool = new AskUserQuestionTool();
        tool.setUserInputProvider((json, timeout) -> answerJson);

        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("id", "ask_user_question", Map.of("questions", questionsJson)))
                .input(Map.of("questions", questionsJson))
                .build();
        ToolResultBlock result = tool.callAsync(param).block();

        assertThat(textOf(result)).contains("A");
        assertThat(textOf(result)).contains("B");
    }

    @Test
    void shouldReturnErrorOnTimeout() {
        String questionsJson = """
                [{"question":"Test?","header":"Q","options":[{"label":"Yes","description":""}],"multiSelect":false}]""";

        AskUserQuestionTool tool = new AskUserQuestionTool();
        tool.setUserInputProvider((json, timeout) -> null);

        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("id", "ask_user_question", Map.of("questions", questionsJson)))
                .input(Map.of("questions", questionsJson))
                .build();
        ToolResultBlock result = tool.callAsync(param).block();

        assertThat(textOf(result)).contains("not respond");
    }
}
