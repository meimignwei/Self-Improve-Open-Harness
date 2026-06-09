package io.openharness.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AskUserQuestionTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final ObjectMapper mapper;
    private volatile UserInputProvider userInputProvider;

    public AskUserQuestionTool() {
        this.mapper = new ObjectMapper();
    }

    @Override
    public String getName() { return "ask_user_question"; }

    @Override
    public String getDescription() { return "Ask the user clarifying questions. Supports single-select and multi-select."; }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> questionsProp = new LinkedHashMap<>();
        questionsProp.put("type", "string");
        questionsProp.put("description", "JSON array of question objects. Each question has: question, header, options (array of {label, description}), multiSelect (boolean)");
        properties.put("questions", questionsProp);
        params.put("properties", properties);
        List<String> required = List.of("questions");
        params.put("required", required);
        return params;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String questionsJson = (String) param.getInput().get("questions");
        if (questionsJson == null || questionsJson.isBlank()) {
            return Mono.just(ToolResultBlock.error("Missing 'questions' argument"));
        }

        UserInputProvider provider = this.userInputProvider;
        if (provider == null) {
            return Mono.just(ToolResultBlock.error("No UserInputProvider configured for AskUserQuestionTool"));
        }

        log.debug("AskUserQuestionTool: waiting for user input (timeout: {})", DEFAULT_TIMEOUT);
        String answerJson = provider.waitForAnswer(questionsJson, DEFAULT_TIMEOUT);

        if (answerJson == null) {
            return Mono.just(ToolResultBlock.error("User did not respond within timeout"));
        }

        try {
            JsonNode answers = mapper.readTree(answerJson);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("answers", mapper.treeToValue(answers, Map.class));
            String display = "## User Answers\n\n```json\n" + mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(answers) + "\n```";
            return Mono.just(ToolResultBlock.of(TextBlock.builder().text(display).build(), metadata));
        } catch (Exception e) {
            return Mono.just(ToolResultBlock.text("## User Answers\n\n" + answerJson));
        }
    }

    public void setUserInputProvider(UserInputProvider provider) {
        this.userInputProvider = provider;
    }

    @FunctionalInterface
    public interface UserInputProvider {
        String waitForAnswer(String questionJson, Duration timeout);
    }
}
