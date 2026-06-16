package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.extensions.swarm.BackendRegistry;
import com.openharness.extensions.swarm.TeammateBackend;
import com.openharness.extensions.swarm.TeammateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Send a follow-up message to a running agent task or swarm teammate.
 * Java equivalent of Python tools/send_message_tool.py.
 */
public class SendMessageTool extends BaseTool<SendMessageTool.Input> {

    private static final Logger logger = LoggerFactory.getLogger(SendMessageTool.class);

    private final MessageSender sender;

    public SendMessageTool(MessageSender sender) {
        super("send_message", "Send a follow-up message to a running local agent task.", Input.class);
        this.sender = sender;
    }

    @Override
    public ToolResult execute(Input args, ToolExecutionContext ctx) {
        // Swarm agents use agent_id format (name@team); legacy tasks use plain task IDs
        if (args.to().contains("@")) {
            return sendSwarmMessage(args.to(), args.message());
        }

        // Legacy task-based messaging
        if (sender == null) {
            return ToolResult.error("No message sender configured.");
        }
        try {
            sender.send(args.to(), args.message());
            return ToolResult.success("Sent message to task " + args.to());
        } catch (Exception e) {
            return ToolResult.error("Failed to send message: " + e.getMessage());
        }
    }

    private ToolResult sendSwarmMessage(String agentId, String message) {
        BackendRegistry registry = BackendRegistry.getInstance();
        TeammateBackend executor = registry.getExecutor("subprocess");

        TeammateMessage msg = new TeammateMessage(message, "coordinator");
        try {
            executor.sendMessage(agentId, msg);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to send message to {}: {}", agentId, e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
        return ToolResult.success("Sent message to agent " + agentId);
    }

    @Override
    public boolean isReadOnly(Input args) {
        return false;
    }

    public record Input(String to, String message) {
        public Input {
            if (to == null || to.isBlank()) throw new IllegalArgumentException("to is required");
            if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
        }
    }

    @FunctionalInterface
    public interface MessageSender {
        void send(String recipientId, String message);
    }
}