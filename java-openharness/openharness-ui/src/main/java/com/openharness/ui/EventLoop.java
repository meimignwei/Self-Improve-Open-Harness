package com.openharness.ui;

import com.openharness.common.*;
import com.openharness.config.Settings;
import com.openharness.extensions.mcp.McpClientManager;
import com.openharness.extensions.mcp.McpConnectionState;

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
    private McpClientManager mcpManager;

    public EventLoop(RuntimeOutput output, Settings settings, AgentRuntime agentRuntime) {
        this.output = output;
        this.settings = settings;
        this.agentRuntime = agentRuntime;
    }

    public void setMcpManager(McpClientManager mcpManager) {
        this.mcpManager = mcpManager;
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
                    case StreamEvent.CompactProgressEvent(var phase, var trigger, var msg,
                                                         var attempt, var checkpoint, var metadata) ->
                            output.emitStatus("[compact:" + phase + "] " + (msg != null ? msg : ""));
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

    private void handleMemoryCommand(String arg) {
        String[] parts = arg.split("\\s+", 2);
        String sub = parts.length > 0 ? parts[0].strip() : "";
        String subArgs = parts.length > 1 ? parts[1].strip() : "";

        java.nio.file.Path memoryDir = com.openharness.config.Paths.memoryDir();
        com.openharness.config.MemorySettings memSettings =
                new com.openharness.config.MemorySettings();
        com.openharness.extensions.memory.MemoryManager mgr =
                new com.openharness.extensions.memory.MemoryManager(memoryDir, memSettings);

        switch (sub) {
            case "list", "ls", "" -> {
                var all = mgr.listAll();
                if (all.isEmpty()) {
                    output.emitStatus("No memories stored.");
                } else {
                    for (var m : all) {
                        output.emitStatus("[" + m.header().type().name().toLowerCase()
                                + "] " + m.header().name()
                                + " (importance:" + m.header().importance() + ")");
                    }
                }
            }
            case "search", "find" -> {
                if (subArgs.isBlank()) {
                    output.emitStatus("Usage: /memory search <query>");
                } else {
                    var results = mgr.search(subArgs, 5);
                    for (var s : results) {
                        output.emitStatus("[" + s.memory().header().type().name().toLowerCase()
                                + "] " + s.memory().header().name()
                                + " (score:" + String.format("%.2f", s.score()) + ")");
                    }
                }
            }
            case "prune" -> {
                int pruned = mgr.pruneExpired();
                output.emitStatus("Pruned " + pruned + " expired memories.");
            }
            case "count" -> {
                output.emitStatus("Total memories: " + mgr.listAll().size());
            }
            default -> output.emitStatus("Usage: /memory [list|search|prune|count]");
        }
    }

    private void handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/help" -> {
                output.emitStatus("Commands: /model /theme /compact /memory /clear /help /exit");
                output.emitStatus("Press Ctrl+C to interrupt, /exit to quit.");
            }
            case "/model" -> {
                if (!arg.isEmpty()) settings.setModel(arg);
                output.emitStatus("Model: " + settings.model());
            }
            case "/compact" -> {
                output.emitStatus("Compacting...");
                String result = agentRuntime.compact();
                output.emitStatus(result);
            }
            case "/memory" -> handleMemoryCommand(arg);
            case "/mcp" -> handleMcpCommand(arg);
            case "/theme" -> output.emitStatus("Theme: " + settings.theme());
            case "/clear" -> System.out.print("\033[H\033[2J");
            default -> output.emitStatus("Unknown command: " + cmd);
        }
    }

    private void handleMcpCommand(String arg) {
        String[] parts = arg.split("\\s+", 3);
        String sub = parts.length > 0 ? parts[0].strip() : "";
        String subArgs1 = parts.length > 1 ? parts[1] : "";
        String subArgs2 = parts.length > 2 ? parts[2] : "";

        if (mcpManager == null) {
            output.emitStatus("MCP is not configured in this session.");
            return;
        }

        switch (sub) {
            case "" -> {
                var statuses = mcpManager.listStatuses();
                if (statuses.isEmpty()) {
                    output.emitStatus("No MCP servers configured.");
                } else {
                    for (var s : statuses) {
                        output.emitStatus("[" + s.state().name().toLowerCase()
                                + "] " + s.name() + " (" + s.transport() + ") "
                                + "tools:" + s.tools().size()
                                + " resources:" + s.resources().size());
                    }
                }
            }
            case "auth" -> {
                if (subArgs1.isBlank()) {
                    output.emitStatus("Usage: /mcp auth <server> <token>");
                    output.emitStatus("       /mcp auth <server> bearer|env <value>");
                    output.emitStatus("       /mcp auth <server> header <key> <value>");
                    return;
                }
                String server = subArgs1;
                String remaining = subArgs2;
                if (remaining.isBlank()) {
                    output.emitStatus("Usage: /mcp auth " + server + " <token>");
                    return;
                }

                // Parse mode and value
                String[] authParts = remaining.split("\\s+", 2);
                String first = authParts[0];
                String rest = authParts.length > 1 ? authParts[1] : "";

                java.util.Map<String, Object> auth;
                if (first.equals("bearer") || first.equals("env")) {
                    auth = java.util.Map.of("type", first, "value", rest.isBlank() ? first : rest,
                            first.equals("bearer") ? "token" : "key",
                            rest.isBlank() ? first : rest);
                } else if (first.equals("header") && !rest.isBlank()) {
                    String[] hp = rest.split("\\s+", 2);
                    String hKey = hp[0];
                    String hVal = hp.length > 1 ? hp[1] : "";
                    auth = java.util.Map.of("type", "header", "key", hKey, "value", hVal);
                } else {
                    // Simple bearer token
                    auth = java.util.Map.of("type", "bearer", "token", first);
                }

                // Persist via McpAuthTool path
                try {
                    var configPath = java.nio.file.Path.of(
                            System.getProperty("user.home"), ".openharness", "mcp_auth.json");
                    var authTool = new com.openharness.tools.McpAuthTool(
                            mcpManager, configPath);
                    authTool.execute(new com.openharness.tools.McpAuthTool.Input(
                            server, "bearer", first, null),
                            new com.openharness.engine.tool.ToolExecutionContext(
                                    java.nio.file.Path.of(".")));
                    output.emitStatus("Auth configured for MCP server '" + server
                            + "'. Reconnecting...");
                    mcpManager.reconnectAll();
                    output.emitStatus("Reconnected. Use /mcp to verify status.");
                } catch (Exception e) {
                    output.emitError("Auth update failed: " + e.getMessage());
                }
            }
            default -> output.emitStatus("Usage: /mcp [auth <server> <token>]");
        }
    }
}
