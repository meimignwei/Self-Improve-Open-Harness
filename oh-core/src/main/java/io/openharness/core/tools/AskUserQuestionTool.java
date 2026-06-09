package io.openharness.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class AskUserQuestionTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final ObjectMapper mapper;
    private volatile UserInputProvider userInputProvider;

    public AskUserQuestionTool() {
        super("ask_user_question",
                "Ask the user clarifying questions. Supports single-select and multi-select.");
        this.mapper = new ObjectMapper();
    }

    @Override
    public ToolResultBlock callSync(ToolCallParam param) {
        String questionsJson = param.getString("questions");
        if (questionsJson == null || questionsJson.isBlank()) {
            return ToolResultBlock.error("Missing 'questions' argument");
        }

        UserInputProvider provider = this.userInputProvider;
        if (provider == null) {
            return ToolResultBlock.error("No UserInputProvider configured for AskUserQuestionTool");
        }

        log.debug("AskUserQuestionTool: waiting for user input (timeout: {})", DEFAULT_TIMEOUT);
        String answerJson = provider.waitForAnswer(questionsJson, DEFAULT_TIMEOUT);

        if (answerJson == null) {
            return ToolResultBlock.error("User did not respond within timeout");
        }

        return formatResult(answerJson);
    }

    public void setUserInputProvider(UserInputProvider provider) {
        this.userInputProvider = provider;
    }

    private ToolResultBlock formatResult(String answerJson) {
        try {
            JsonNode answers = mapper.readTree(answerJson);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("answers", mapper.treeToValue(answers, Map.class));
            return ToolResultBlock.success(
                    "## User Answers\n\n```json\n" + mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(answers) + "\n```",
                    metadata);
        } catch (Exception e) {
            return ToolResultBlock.success("## User Answers\n\n" + answerJson);
        }
    }

    @FunctionalInterface
    public interface UserInputProvider {
        String waitForAnswer(String questionJson, Duration timeout);
    }
}
