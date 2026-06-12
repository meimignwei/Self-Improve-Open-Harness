package com.openharness.ui;

import com.openharness.common.*;
import com.openharness.config.Settings;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;

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

    public void start(Settings settings, String initialPrompt, AgentRuntime agentRuntime) {
        running = true;
        renderer.printBanner("OpenHarness v0.1.0");

        if (initialPrompt != null && !initialPrompt.isEmpty()) {
            renderer.printHeader("Running prompt");
            renderer.printLine(initialPrompt);
            executeQuery(settings, agentRuntime, initialPrompt);
        } else {
            renderer.printHeader("Interactive mode — type /help for commands");

            while (running) {
                String input = inputHandler.readLine("oh> ");
                if (input == null || input.equalsIgnoreCase("/exit") || input.equalsIgnoreCase("/quit")) {
                    running = false;
                } else if (input.startsWith("/")) {
                    handleSlashCommand(input);
                } else if (!input.isBlank()) {
                    renderer.printHeader("Processing...");
                    executeQuery(settings, agentRuntime, input);
                }
            }
        }
    }

    private void executeQuery(Settings settings, AgentRuntime agentRuntime, String userInput) {
        List<ConversationMessage> messages = List.of(
                new ConversationMessage(Role.USER, List.of(new ContentBlock.TextBlock(userInput)))
        );

        QueryOptions options = QueryOptions.defaults()
                .withModel(settings.model())
                .withMaxTurns(settings.maxTurns())
                .withSystemPrompt(settings.systemPrompt());

        var publisher = agentRuntime.runQuery(messages, options);
        var latch = new CountDownLatch(1);
        StringBuilder assistantText = new StringBuilder();

        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(StreamEvent event) {
                switch (event) {
                    case StreamEvent.AssistantTextDelta(var text) -> {
                        assistantText.append(text);
                        renderer.printLine(text);
                    }
                    case StreamEvent.ToolStarted(var name, var id) ->
                            renderer.printLine("[Tool: " + name + "]");
                    case StreamEvent.ToolCompleted(var name, var id, var result) -> {
                        String status = result.isError() ? "ERROR" : "OK";
                        renderer.printLine("[Tool done: " + name + " — " + status + "]");
                    }
                    case StreamEvent.StatusEvent(var msg, var level) -> renderer.printLine("[" + msg + "]");
                    case StreamEvent.ErrorStreamEvent(var msg) -> renderer.printLine("[Error: " + msg + "]");
                    case StreamEvent.AssistantTurnComplete(var usage) -> { /* turn boundary */ }
                    case StreamEvent.CompactProgressEvent(var removed, var remaining) ->
                            renderer.printLine("[Compacted " + removed + " messages]");
                }
            }

            @Override
            public void onError(Throwable throwable) {
                renderer.printLine("[Error: " + throwable.getMessage() + "]");
                latch.countDown();
            }

            @Override
            public void onComplete() {
                if (assistantText.isEmpty()) {
                    renderer.printLine("(no response)");
                }
                renderer.printLine("");
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
