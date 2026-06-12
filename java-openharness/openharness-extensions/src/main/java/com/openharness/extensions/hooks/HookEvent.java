package com.openharness.extensions.hooks;

/**
 * 10 hook event types corresponding to the agent lifecycle.
 * Java equivalent of Python's HookEvent enum.
 */
public enum HookEvent {
    SESSION_START,
    SESSION_END,
    PRE_COMPACT,
    POST_COMPACT,
    PRE_TOOL_USE,
    POST_TOOL_USE,
    USER_PROMPT_SUBMIT,
    NOTIFICATION,
    STOP,
    SUBAGENT_STOP
}
