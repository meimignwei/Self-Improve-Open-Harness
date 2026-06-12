package com.openharness.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.common.ToolResult;

/**
 * Output abstraction supporting three runtime modes: TUI, BACKEND, PRINT.
 * Java equivalent of Python ui/runtime.py.
 */
public interface RuntimeOutput {

    enum Mode { TUI, BACKEND, PRINT }

    void emitStatus(String message);

    void emitAssistantDelta(String text);

    void emitToolStarted(String toolName, JsonNode args);

    void emitToolCompleted(String toolName, ToolResult result);

    void emitError(String message);

    void emitShutdown();

    String readInput();

    default void emitReady(String sessionId) {}
}
