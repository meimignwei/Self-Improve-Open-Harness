package com.openharness.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.common.ToolResult;

import java.io.PrintStream;
import java.util.Scanner;

/**
 * Plain-text stdout mode for scripting.
 * Java equivalent of Python ui/output.py print mode.
 */
public class PrintOutput implements RuntimeOutput {

    private final PrintStream out;
    private final Scanner scanner;

    public PrintOutput(PrintStream out, Scanner scanner) {
        this.out = out;
        this.scanner = scanner;
    }

    public PrintOutput() {
        this(System.out, new Scanner(System.in));
    }

    @Override
    public void emitStatus(String message) {
        out.println("[STATUS] " + message);
    }

    @Override
    public void emitAssistantDelta(String text) {
        out.print(text);
        out.flush();
    }

    @Override
    public void emitToolStarted(String toolName, JsonNode args) {
        out.println("[TOOL] " + toolName + " " + args);
    }

    @Override
    public void emitToolCompleted(String toolName, ToolResult result) {
        out.println("[TOOL_DONE] " + toolName + (result.isError() ? " ERROR" : " OK"));
    }

    @Override
    public void emitError(String message) {
        out.println("[ERROR] " + message);
    }

    @Override
    public void emitShutdown() {
        out.println("[SHUTDOWN]");
    }

    @Override
    public String readInput() {
        out.print("> ");
        out.flush();
        if (scanner.hasNextLine()) {
            return scanner.nextLine();
        }
        return null;
    }
}
