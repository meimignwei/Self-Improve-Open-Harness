package io.openharness.cli.tui;

import io.openharness.cli.session.SessionManager;
import io.openharness.core.AgentAssembler;
import io.openharness.core.commands.CommandRegistry;
import io.openharness.core.session.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalUI {

    private static final Logger log = LoggerFactory.getLogger(TerminalUI.class);

    private final AgentAssembler assembler;
    private final SessionManager sessionManager;
    private final CommandRegistry commandRegistry;
    private final SessionContext ctx;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public TerminalUI(AgentAssembler assembler, SessionManager sessionManager,
                      CommandRegistry commandRegistry, SessionContext ctx) {
        this.assembler = assembler;
        this.sessionManager = sessionManager;
        this.commandRegistry = commandRegistry;
        this.ctx = ctx;
    }

    public int runInteractive() {
        System.out.println(AnsiRenderer.blue(AnsiRenderer.bold("OpenHarness v0.1.0 - Interactive REPL")));
        System.out.println(AnsiRenderer.dim("Type /help for available commands. Ctrl+D to exit."));
        System.out.println();

        var agent = assembler.assemble(ctx);
        System.out.println(AnsiRenderer.dim("Agent assembled: " + agent.getModel().getModelName()));
        System.out.println();

        try (var scanner = new java.util.Scanner(System.in)) {
            while (running.get()) {
                System.out.print(AnsiRenderer.prompt());
                String line = null;
                try {
                    if (!scanner.hasNextLine()) break;
                    line = scanner.nextLine();
                } catch (Exception e) {
                    break;
                }

                if (line == null || line.isBlank()) continue;

                String input = line.trim();

                if ("/exit".equals(input) || "/quit".equals(input)) {
                    System.out.println(AnsiRenderer.dim("Goodbye."));
                    break;
                }

                if (input.startsWith("/")) {
                    handleSlashCommand(input);
                } else {
                    System.out.println(AnsiRenderer.yellow("Agent processing... (ReAct loop stub — Phase 4+)"));
                    System.out.println(AnsiRenderer.dim("  Input: " + input));
                }
            }
        }

        sessionManager.flushAll();
        System.out.println(AnsiRenderer.dim("Session saved."));
        return 0;
    }

    public int runOnce(String initialPrompt) {
        var agent = assembler.assemble(ctx);
        System.out.println(AnsiRenderer.dim("Running in single-prompt mode with: " + agent.getModel().getModelName()));
        System.out.println(AnsiRenderer.yellow("Agent processing... (ReAct loop stub — Phase 4+)"));
        System.out.println(AnsiRenderer.dim("  Prompt: " + initialPrompt));
        sessionManager.flushAll();
        return 0;
    }

    private void handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmdName = parts[0].substring(1);
        java.util.List<String> args = parts.length > 1
                ? java.util.List.of(parts[1].split("\\s+"))
                : java.util.List.of();

        if ("/help".equals(input)) {
            System.out.println("Available commands:");
            commandRegistry.listAll().forEach(cmd ->
                System.out.printf("  /%-15s %s%n", cmd.name(), cmd.description()));
            return;
        }

        var cmd = commandRegistry.get(cmdName);
        if (cmd != null) {
            cmd.execute(args).block();
        } else {
            System.out.println(AnsiRenderer.yellow("Unknown command: " + input + " (type /help for commands)"));
        }
    }

    public void stop() {
        running.set(false);
    }
}
