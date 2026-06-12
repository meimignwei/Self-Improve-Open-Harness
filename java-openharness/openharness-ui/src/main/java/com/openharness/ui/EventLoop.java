package com.openharness.ui;

import com.openharness.config.Settings;

import java.util.Scanner;

/**
 * Main event loop: reads user input, dispatches to engine, renders output.
 * Java equivalent of Python ui/event_loop.py.
 */
public class EventLoop {

    private final RuntimeOutput output;
    private final Settings settings;
    private final Scanner scanner;

    public EventLoop(RuntimeOutput output, Settings settings) {
        this.output = output;
        this.settings = settings;
        this.scanner = new Scanner(System.in);
    }

    public void run() {
        output.emitStatus("OpenHarness v0.1.0 ready. Model: " + settings.model());

        while (true) {
            System.out.print("> ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("/exit") || line.equalsIgnoreCase("/quit")) {
                output.emitStatus("Goodbye.");
                break;
            }

            if (line.startsWith("/")) {
                handleSlashCommand(line);
                continue;
            }

            // Process user input through the engine
            output.emitAssistantDelta(line);
            output.emitStatus("Processing: " + truncate(line, 60));
        }

        output.emitShutdown();
    }

    private void handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/help" -> {
                output.emitStatus("Commands: /model /theme /clear /help /exit");
                output.emitStatus("Press Ctrl+C to interrupt, /exit to quit.");
            }
            case "/model" -> {
                if (!arg.isEmpty()) settings.setModel(arg);
                output.emitStatus("Model: " + settings.model());
            }
            case "/theme" -> output.emitStatus("Theme: " + settings.theme());
            case "/clear" -> System.out.print("\033[H\033[2J");
            default -> output.emitStatus("Unknown command: " + cmd);
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
