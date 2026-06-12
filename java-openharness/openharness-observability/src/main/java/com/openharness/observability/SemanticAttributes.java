package com.openharness.observability;

/**
 * Standardized semantic attribute constants for OpenTelemetry spans.
 * Java equivalent of OpenTelemetry's SemanticAttributes.
 */
public final class SemanticAttributes {

    private SemanticAttributes() {}

    // Agent
    public static final String AGENT_SESSION_ID = "agent.session.id";
    public static final String AGENT_TURN_NUMBER = "agent.turn.number";
    public static final String AGENT_PERMISSION_MODE = "agent.permission.mode";
    public static final String AGENT_TOOL_COUNT = "agent.tool.count";

    // LLM
    public static final String LLM_PROVIDER = "llm.provider";
    public static final String LLM_MODEL = "llm.model";
    public static final String LLM_INPUT_TOKENS = "llm.input_tokens";
    public static final String LLM_OUTPUT_TOKENS = "llm.output_tokens";
    public static final String LLM_STOP_REASON = "llm.stop_reason";
    public static final String LLM_COST_USD = "llm.cost.usd";

    // Tool
    public static final String TOOL_NAME = "tool.name";
    public static final String TOOL_REQUIRES_APPROVAL = "tool.requires_approval";
    public static final String TOOL_EXIT_CODE = "tool.exit_code";
    public static final String TOOL_OUTPUT_BYTES = "tool.output.bytes";

    // Channel
    public static final String CHANNEL_TYPE = "channel.type";
    public static final String CHANNEL_CHAT_ID = "channel.chat_id";
    public static final String CHANNEL_SESSION_KEY = "channel.session_key";

    // Error
    public static final String ERROR_TYPE = "error.type";
    public static final String ERROR_MESSAGE = "error.message";
    public static final String ERROR_STATUS_CODE = "error.status_code";
}
