package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Sends a message to another agent via the FileMailbox.
 * Java equivalent of Python's send_message tool.
 */
public class SendMessageTool extends BaseTool<SendMessageTool.Input> {

    private final MessageSender sender;

    public SendMessageTool(MessageSender sender) {
        super("send_message", "Send a message to another agent or team member.", Input.class);
        this.sender = sender;
    }

    @Override
    public ToolResult execute(Input args, ToolExecutionContext ctx) {
        if (sender == null) {
            return ToolResult.error("No message sender configured.");
        }
        try {
            sender.send(args.to(), args.message());
            return ToolResult.success("Message sent to: " + args.to());
        } catch (Exception e) {
            return ToolResult.error("Failed to send message: " + e.getMessage());
        }
    }

    @Override public boolean isReadOnly(Input args) { return false; }

    public record Input(String to, String message) {
        public Input {
            if (to == null) throw new IllegalArgumentException("to is required");
            if (message == null) throw new IllegalArgumentException("message is required");
        }
    }

    @FunctionalInterface
    public interface MessageSender {
        void send(String recipientId, String message);
    }
}
