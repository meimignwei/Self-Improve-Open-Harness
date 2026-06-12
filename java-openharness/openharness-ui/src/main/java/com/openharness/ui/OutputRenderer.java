package com.openharness.ui;

import java.io.PrintWriter;

/**
 * ANSI terminal output renderer with theme support.
 * Java equivalent of Python ui/output.py.
 */
public class OutputRenderer {

    private static final String ANSI_RESET = "[0m";
    private static final String ANSI_BOLD = "[1m";
    private static final String ANSI_DIM = "[2m";
    private static final String ANSI_CYAN = "[36m";
    private static final String ANSI_GREEN = "[32m";
    private static final String ANSI_YELLOW = "[33m";
    private static final String ANSI_RED = "[31m";
    private static final String ANSI_BLUE = "[34m";

    private final PrintWriter writer;

    public OutputRenderer() {
        this.writer = new PrintWriter(System.out, true);
    }

    public void printBanner(String text) {
        writer.println(ANSI_BOLD + ANSI_CYAN + "╔══ " + text + " ══╗" + ANSI_RESET);
    }

    public void printHeader(String text) {
        writer.println(ANSI_BOLD + ANSI_BLUE + "── " + text + " ──" + ANSI_RESET);
    }

    public void printLine(String text) {
        writer.println(text);
    }

    public void printDelta(String text) {
        writer.print(ANSI_GREEN + text + ANSI_RESET);
        writer.flush();
    }

    public void printStatus(String message) {
        writer.println(ANSI_DIM + "  [" + ANSI_BOLD + "···" + ANSI_RESET + ANSI_DIM + "] " + message + ANSI_RESET);
    }

    public void printToolCall(String toolName) {
        writer.println(ANSI_YELLOW + "  [→] " + toolName + ANSI_RESET);
    }

    public void printToolResult(String toolName, boolean success) {
        String icon = success ? "✓" : "✗";
        String color = success ? ANSI_GREEN : ANSI_RED;
        writer.println(color + "  [" + icon + "] " + toolName + ANSI_RESET);
    }

    public void printError(String message) {
        writer.println(ANSI_RED + "  [✗] " + message + ANSI_RESET);
    }

    public void printWarning(String message) {
        writer.println(ANSI_YELLOW + "  [!] " + message + ANSI_RESET);
    }

    public void clear() {
        writer.print("\033[H\033[2J");
        writer.flush();
    }

    public void flush() {
        writer.flush();
    }
}
