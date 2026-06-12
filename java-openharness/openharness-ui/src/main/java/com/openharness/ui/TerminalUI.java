package com.openharness.ui;

import com.openharness.config.Settings;

import java.io.IOException;
import java.util.List;

/**
 * JLine 3 interactive terminal UI.
 * Java equivalent of Python's Rich/prompt-toolkit-based terminal UI.
 */
public class TerminalUI {

    private final InputHandler inputHandler;
    private final OutputRenderer renderer;
    private final PermissionDialog permissionDialog;
    private volatile boolean running;

    public TerminalUI() {
        this.inputHandler = new InputHandler();
        this.renderer = new OutputRenderer();
        this.permissionDialog = new PermissionDialog();
    }

    public void start(Settings settings, String initialPrompt) {
        running = true;
        renderer.printBanner("OpenHarness v0.1.0");

        if (initialPrompt != null && !initialPrompt.isEmpty()) {
            renderer.printHeader("Running prompt");
            renderer.printLine(initialPrompt);
        } else {
            EventLoop loop = new EventLoop(RuntimeFactory.create(RuntimeOutput.Mode.PRINT), settings);
            renderer.printHeader("Interactive mode — type /help for commands");

            while (running) {
                String input = inputHandler.readLine("oh> ");
                if (input == null || input.equalsIgnoreCase("/exit") || input.equalsIgnoreCase("/quit")) {
                    running = false;
                } else if (input.startsWith("/")) {
                    handleSlashCommand(input);
                } else if (!input.isBlank()) {
                    renderer.printHeader("Processing...");
                    // Dispatch to engine via EventLoop
                    renderer.printLine("[engine response would render here]");
                }
            }
        }
    }

    private void handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "/help" -> printHelp();
            case "/model" -> renderer.printLine("Current model from settings");
            case "/theme" -> renderer.printLine("Theme: configured via settings.json");
            case "/clear" -> renderer.clear();
            case "/vim" -> renderer.printLine("Vim mode toggled");
            case "/voice" -> renderer.printLine("Voice mode toggled");
            default -> renderer.printLine("Unknown command: " + cmd + " (type /help)");
        }
    }

    private void printHelp() {
        renderer.printHeader("Available commands");
        for (String[] cmd : List.of(
                new String[]{"/help", "Show this help"},
                new String[]{"/model <name>", "Switch model"},
                new String[]{"/theme <name>", "Switch theme"},
                new String[]{"/clear", "Clear screen"},
                new String[]{"/vim", "Toggle vim mode"},
                new String[]{"/voice", "Toggle voice input"},
                new String[]{"/exit, /quit", "Exit"})
        ) {
            renderer.printLine("  " + cmd[0] + "  — " + cmd[1]);
        }
    }

    public PermissionDialog permissionDialog() { return permissionDialog; }

    public void stop() {
        running = false;
        try {
            inputHandler.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
