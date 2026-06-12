package com.openharness.tools;

import com.openharness.common.AgentRuntime;
import com.openharness.common.ConversationMessage;
import com.openharness.common.QueryOptions;
import com.openharness.common.Role;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Spawn a sub-agent to handle a task autonomously.
 * Only depends on the AgentRuntime interface (not QueryEngine), breaking the circular dep.
 */
public class AgentTool extends BaseTool<AgentTool.Input> {

    private final AgentRuntime agentRuntime;

    public AgentTool(AgentRuntime agentRuntime) {
        super("agent", "Launch a sub-agent to handle a complex task.", Input.class);
        this.agentRuntime = agentRuntime;
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        var messages = java.util.List.of(
                new ConversationMessage(Role.USER,
                        java.util.List.of(new com.openharness.common.ContentBlock.TextBlock(arguments.prompt()))));

        QueryOptions opts = QueryOptions.defaults()
                .withModel(arguments.model() != null ? arguments.model() : null);

        var events = com.openharness.common.PublisherAdapter.toList(
                agentRuntime.runQuery(messages, opts));

        StringBuilder output = new StringBuilder();
        boolean hasError = false;

        for (var event : events) {
            if (event instanceof com.openharness.common.StreamEvent.AssistantTextDelta delta) {
                output.append(delta.text());
            } else if (event instanceof com.openharness.common.StreamEvent.ErrorStreamEvent err) {
                output.append("Error: ").append(err.message());
                hasError = true;
            }
        }

        return new ToolResult(output.toString(), hasError);
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
    }

    public record Input(String prompt, String model) {}
}
