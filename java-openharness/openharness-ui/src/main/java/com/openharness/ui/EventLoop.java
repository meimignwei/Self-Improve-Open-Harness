package com.openharness.ui;

import com.openharness.common.*;
import com.openharness.config.Settings;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * Main event loop: reads user input, dispatches to engine, renders output.
 * Java equivalent of Python ui/event_loop.py.
 */
public class EventLoop {

    private final RuntimeOutput output;
    private final Settings settings;
    private final AgentRuntime agentRuntime;

    public EventLoop(RuntimeOutput output, Settings settings, AgentRuntime agentRuntime) {
        this.output = output;
        this.settings = settings;
        this.agentRuntime = agentRuntime;
    }

    public void run() {
        output.emitStatus("OpenHarness v0.1.0 ready. Model: " + settings.model());

        while (true) {
            String line = output.readInput();
            if (line == null) break;
            line = line.trim();

            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("/exit") || line.equalsIgnoreCase("/quit")) {
                output.emitStatus("Goodbye.");
                break;
            }

            if (line.startsWith("/")) {
                handleSlashCommand(line);
                continue;
            }

            executeQuery(line);
        }

        output.emitShutdown();
    }

    private void executeQuery(String userInput) {
        List<ConversationMessage> messages = List.of(
                new ConversationMessage(Role.USER, List.of(new ContentBlock.TextBlock(userInput)))
        );

        QueryOptions options = QueryOptions.defaults()
                .withModel(settings.model())
                .withMaxTurns(settings.maxTurns())
                .withSystemPrompt(settings.systemPrompt());

        var publisher = agentRuntime.runQuery(messages, options);
        var latch = new java.util.concurrent.CountDownLatch(1);

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
                    case StreamEvent.AssistantTextDelta(var text) -> output.emitAssistantDelta(text);
                    case StreamEvent.ToolStarted(var name, var id) -> output.emitToolStarted(name, null);
                    case StreamEvent.ToolCompleted(var name, var id, var result) ->
                            output.emitToolCompleted(name, result);
                    case StreamEvent.StatusEvent(var msg, var level) -> output.emitStatus(msg);
                    case StreamEvent.ErrorStreamEvent(var msg) -> output.emitError(msg);
                    case StreamEvent.AssistantTurnComplete(var usage) -> { /* turn boundary */ }
                    case StreamEvent.CompactProgressEvent(var removed, var remaining) ->
                            output.emitStatus("Compacted " + removed + " messages");
                }
            }

            @Override
            public void onError(Throwable throwable) {
                output.emitError("Stream error: " + throwable.getMessage());
                latch.countDown();
            }

            @Override
            public void onComplete() {
                output.emitAssistantDelta("\n");
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
}
