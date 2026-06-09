package io.openharness.unit;

import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolResultBlock;
import io.openharness.core.tools.AskUserQuestionTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AskUserQuestionToolTest {

    @Test
    void shouldReturnAnswerForSingleSelectQuestion() {
        String questionsJson = """
                [{"question":"Which framework?","header":"FW","options":[{"label":"Spring","description":"Java standard"}],"multiSelect":false}]""";
        String answerJson = """
                {"FW":"Spring"}""";

        AskUserQuestionTool tool = new AskUserQuestionTool();
        tool.setUserInputProvider((json, timeout) -> answerJson);

        ToolCallParam param = new ToolCallParam("ask_user_question", Map.of("questions", questionsJson));
        ToolResultBlock result = tool.callSync(param);

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("Spring");
    }

    @Test
    void shouldHandleMultiSelect() {
        String questionsJson = """
                [{"question":"Which features?","header":"Features","options":[{"label":"A","description":"..."},{"label":"B","description":"..."}],"multiSelect":true}]""";
        String answerJson = """
                {"Features":["A","B"]}""";

        AskUserQuestionTool tool = new AskUserQuestionTool();
        tool.setUserInputProvider((json, timeout) -> answerJson);

        ToolCallParam param = new ToolCallParam("ask_user_question", Map.of("questions", questionsJson));
        ToolResultBlock result = tool.callSync(param);

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("A");
        assertThat(result.content()).contains("B");
    }

    @Test
    void shouldReturnErrorOnTimeout() {
        String questionsJson = """
                [{"question":"Test?","header":"Q","options":[{"label":"Yes","description":""}],"multiSelect":false}]""";

        AskUserQuestionTool tool = new AskUserQuestionTool();
        tool.setUserInputProvider((json, timeout) -> null);

        ToolCallParam param = new ToolCallParam("ask_user_question", Map.of("questions", questionsJson));
        ToolResultBlock result = tool.callSync(param);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("not respond");
    }
}
