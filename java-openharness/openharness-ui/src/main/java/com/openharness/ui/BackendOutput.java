package com.openharness.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * JSON-Lines backend mode — stdin/stdout protocol for React/Ink frontend.
 * Java equivalent of Python ui/backend_host.py.
 */
public class BackendOutput implements RuntimeOutput {

    private final PrintStream out;
    private final BufferedReader in;
    private final String sessionId;

    public BackendOutput(PrintStream out, BufferedReader in, String sessionId) {
        this.out = out;
        this.in = in;
        this.sessionId = sessionId;
    }

    public BackendOutput() {
        this(System.out, new BufferedReader(new InputStreamReader(System.in)),
                java.util.UUID.randomUUID().toString());
    }

    @Override
    public void emitReady(String sessionId) {
        JsonLinesProtocol.emit(new BackendEvent.ReadyEvent(sessionId), out);
    }

    @Override
    public void emitStatus(String message) {
        JsonLinesProtocol.emit(new BackendEvent.StatusEvent(message, "info"), out);
    }

    @Override
    public void emitAssistantDelta(String text) {
        JsonLinesProtocol.emit(new BackendEvent.AssistantDeltaEvent(text, 0), out);
    }

    @Override
    public void emitToolStarted(String toolName, JsonNode args) {
        JsonLinesProtocol.emit(new BackendEvent.ToolStartedEvent(toolName, args,
                java.util.UUID.randomUUID().toString()), out);
    }

    @Override
    public void emitToolCompleted(String toolName, ToolResult result) {
        JsonLinesProtocol.emit(new BackendEvent.ToolCompletedEvent(toolName,
                "", result), out);
    }

    @Override
    public void emitError(String message) {
        JsonLinesProtocol.emit(new BackendEvent.ErrorEvent(message, null, null), out);
    }

    @Override
    public void emitShutdown() {
        JsonLinesProtocol.emit(new BackendEvent.ShutdownEvent("normal"), out);
    }

    @Override
    public String readInput() {
        try {
            String line = in.readLine();
            if (line == null) return null;
            FrontendRequest req = JsonLinesProtocol.deserialize(line);
            if (req instanceof FrontendRequest.UserInputRequest uir) {
                return uir.text();
            }
            return line;
        } catch (Exception e) {
            return null;
        }
    }
}
