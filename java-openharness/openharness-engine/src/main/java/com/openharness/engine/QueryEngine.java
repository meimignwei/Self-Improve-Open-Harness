package com.openharness.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.api.StreamOptions;
import com.openharness.api.StreamingApiClient;
import com.openharness.api.ToolDefinition;
import com.openharness.common.*;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.engine.tool.ToolRegistry;
import com.openharness.permissions.PermissionChecker;
import com.openharness.permissions.PermissionDecision;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.stream.Stream;

/**
 * Core Agent loop engine.
 * Java equivalent of Python's QueryEngine — implements the LLM + tool execution loop
 * using Virtual Threads and StructuredTaskScope.
 */
public class QueryEngine implements AgentRuntime {

    private final StreamingApiClient apiClient;
    private final ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private final CostTracker costTracker;
    private final AutoCompactState autoCompactState;
    private final AutoCompactCallback autoCompactCallback;
    private final ToolCarryover toolCarryover;
    private final java.util.function.BiFunction<String, String, Boolean> confirmCallback;
    /** Callback invoked after each turn for auto memory extraction. */
    private final java.util.function.Consumer<List<ConversationMessage>> afterTurnCallback;
    /** Callback to prune expired memories. */
    private final Runnable memoryPruner;
    /** Callback invoked at end of executeLoop for session checkpoint (Level 2 compaction). */
    private final java.util.function.Consumer<List<ConversationMessage>> sessionEndCallback;

    public QueryEngine(StreamingApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker) {
        this(apiClient, toolRegistry, permissionChecker, new CostTracker(), null, null, null, null);
    }

    public QueryEngine(StreamingApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, CostTracker costTracker,
                       AutoCompactState autoCompactState, ToolCarryover toolCarryover) {
        this(apiClient, toolRegistry, permissionChecker, costTracker, autoCompactState,
                null, toolCarryover, null);
    }

    public QueryEngine(StreamingApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, CostTracker costTracker,
                       AutoCompactState autoCompactState, ToolCarryover toolCarryover,
                       java.util.function.BiFunction<String, String, Boolean> confirmCallback) {
        this(apiClient, toolRegistry, permissionChecker, costTracker, autoCompactState,
                null, toolCarryover, confirmCallback);
    }

    public QueryEngine(StreamingApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, CostTracker costTracker,
                       AutoCompactState autoCompactState,
                       AutoCompactCallback autoCompactCallback,
                       ToolCarryover toolCarryover,
                       java.util.function.BiFunction<String, String, Boolean> confirmCallback) {
        this(apiClient, toolRegistry, permissionChecker, costTracker, autoCompactState,
                autoCompactCallback, toolCarryover, confirmCallback, null, null, null);
    }

    public QueryEngine(StreamingApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, CostTracker costTracker,
                       AutoCompactState autoCompactState, ToolCarryover toolCarryover,
                       java.util.function.BiFunction<String, String, Boolean> confirmCallback,
                       java.util.function.Consumer<List<ConversationMessage>> afterTurnCallback,
                       java.util.function.Function<List<ConversationMessage>, List<ConversationMessage>> compactFn) {
        this(apiClient, toolRegistry, permissionChecker, costTracker, autoCompactState,
                null, toolCarryover, confirmCallback, afterTurnCallback, null, null);
    }

    public QueryEngine(StreamingApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, CostTracker costTracker,
                       AutoCompactState autoCompactState,
                       AutoCompactCallback autoCompactCallback,
                       ToolCarryover toolCarryover,
                       java.util.function.BiFunction<String, String, Boolean> confirmCallback,
                       java.util.function.Consumer<List<ConversationMessage>> afterTurnCallback,
                       Runnable memoryPruner,
                       java.util.function.Consumer<List<ConversationMessage>> sessionEndCallback) {
        this.apiClient = apiClient;
        this.toolRegistry = toolRegistry;
        this.permissionChecker = permissionChecker;
        this.costTracker = costTracker;
        this.autoCompactState = autoCompactState;
        this.autoCompactCallback = autoCompactCallback;
        this.toolCarryover = toolCarryover;
        this.confirmCallback = confirmCallback;
        this.afterTurnCallback = afterTurnCallback;
        this.memoryPruner = memoryPruner;
        this.sessionEndCallback = sessionEndCallback;
    }

    @Override
    public Flow.Publisher<StreamEvent> runQuery(List<ConversationMessage> messages,
                                                 QueryOptions options) {
        SubmissionPublisher<StreamEvent> publisher = new SubmissionPublisher<>();
        Thread.startVirtualThread(() -> {
            try {
                executeLoop(messages, options, publisher);
            } catch (Exception e) {
                publisher.submit(new StreamEvent.ErrorStreamEvent(
                        "Agent loop failed: " + e.getMessage()));
            } finally {
                publisher.close();
            }
        });
        return publisher;
    }

    @Override
    public String compact() {
        StringBuilder result = new StringBuilder();
        int actions = 0;

        if (toolCarryover != null) {
            int before = toolCarryover.size();
            toolCarryover.compact();
            int after = toolCarryover.size();
            if (before != after) {
                result.append("Tool carryover: ").append(before)
                        .append(" -> ").append(after).append(" items. ");
                actions++;
            }
        }

        if (memoryPruner != null) {
            try {
                memoryPruner.run();
                result.append("Memories pruned. ");
                actions++;
            } catch (Exception e) {
                // best-effort
            }
        }

        if (autoCompactState != null) {
            autoCompactState.resetFailures();
            result.append("Compaction state reset. ");
            actions++;
        }

        return actions > 0 ? result.toString().strip()
                : "Nothing to compact.";
    }

    private void executeLoop(List<ConversationMessage> messages, QueryOptions options,
                              SubmissionPublisher<StreamEvent> publisher) {
        int maxTurns = options.maxTurns().orElse(10);
        String model = options.model().orElse("claude-sonnet-4-6");
        String baseSystemPrompt = options.systemPrompt().orElse(null);
        Path cwd = options.workingDirectory()
                .map(Path::of)
                .orElse(Path.of("").toAbsolutePath());

        String carryoverSnippet = toolCarryover != null ? toolCarryover.buildPromptSnippet() : "";
        String systemPrompt = (baseSystemPrompt != null ? baseSystemPrompt : "")
                + carryoverSnippet;
        if (systemPrompt.isBlank()) systemPrompt = null;

        List<ConversationMessage> conversation = new ArrayList<>(messages);
        List<ToolDefinition> toolDefs = buildToolDefinitions(options.allowedTools().orElse(null));

        for (int turn = 0; turn < maxTurns; turn++) {
            // Auto-compaction check — matching Python's auto_compact_if_needed()
            if (autoCompactCallback != null && autoCompactState != null) {
                var compactResult = autoCompactCallback.apply(conversation, model);
                if (compactResult.wasCompacted()) {
                    conversation = compactResult.messages();
                    publisher.submit(new StreamEvent.StatusEvent(
                            "Auto-compaction complete", StreamEvent.StatusLevel.INFO));
                }
            }

            StreamOptions streamOpts = StreamOptions.defaults()
                    .withSystemPrompt(systemPrompt);

            Stream<ApiStreamEvent> apiStream = apiClient.streamMessages(
                    model, systemPrompt, conversation, toolDefs, streamOpts);

            TurnResult result = processApiStream(apiStream, publisher);

            if (result.error() != null) {
                publisher.submit(new StreamEvent.ErrorStreamEvent(result.error()));
                return;
            }

            if (result.usage() != null) {
                costTracker.add(result.usage(), model);
            }

            List<ContentBlock> responseBlocks = result.blocks();
            if (responseBlocks.isEmpty()) {
                publisher.submit(new StreamEvent.ErrorStreamEvent("Empty response from LLM"));
                return;
            }

            List<ContentBlock> toolUses = responseBlocks.stream()
                    .filter(b -> b instanceof ContentBlock.ToolUseBlock)
                    .toList();

            if (toolUses.isEmpty()) {
                publisher.submit(new StreamEvent.AssistantTurnComplete(result.usage()));
                saveSessionCheckpoint(conversation);
                return;
            }

            // Add assistant response to conversation
            conversation.add(new ConversationMessage(Role.ASSISTANT, responseBlocks));

            // Execute tools in parallel
            List<ContentBlock> toolResults = executeTools(toolUses, cwd, publisher);
            conversation.add(new ConversationMessage(Role.USER, toolResults));

            // Auto-extract durable memories from the turn
            extractMemories(conversation);

            publisher.submit(new StreamEvent.StatusEvent(
                    "Turn " + (turn + 1) + " complete, " + toolUses.size() + " tool(s) executed",
                    StreamEvent.StatusLevel.INFO));
        }

        saveSessionCheckpoint(conversation);
        publisher.submit(new StreamEvent.ErrorStreamEvent(
                "Max turns (" + maxTurns + ") exceeded"));
    }

    private void saveSessionCheckpoint(List<ConversationMessage> conversation) {
        if (sessionEndCallback != null) {
            try {
                sessionEndCallback.accept(conversation);
            } catch (Exception e) {
                // Best-effort
            }
        }
    }

    /**
     * Process the API stream and collect response blocks.
     */
    private TurnResult processApiStream(Stream<ApiStreamEvent> stream,
                                         SubmissionPublisher<StreamEvent> publisher) {
        List<ContentBlock> blocks = new ArrayList<>();
        Map<String, String> toolNames = new HashMap<>();
        Map<String, JsonNode> toolInputs = new HashMap<>();
        StringBuilder textBuilder = new StringBuilder();
        UsageSnapshot usage = null;

        for (ApiStreamEvent event : (Iterable<ApiStreamEvent>) stream::iterator) {
            switch (event) {
                case ApiStreamEvent.ContentDelta(var text, var index) -> {
                    textBuilder.append(text);
                    publisher.submit(new StreamEvent.AssistantTextDelta(text));
                }
                case ApiStreamEvent.ToolUseStart(var id, var name) -> {
                    flushText(textBuilder, blocks);
                    toolNames.put(id, name);
                }
                case ApiStreamEvent.ToolUseInputDelta(var id, var inputJson) -> {
                }
                case ApiStreamEvent.ToolUseComplete(var id, var name, var input) -> {
                    blocks.add(new ContentBlock.ToolUseBlock(id, name, input));
                    toolInputs.put(id, input);
                    toolNames.remove(id);
                }
                case ApiStreamEvent.TurnComplete(var u, var stopReason) -> {
                    flushText(textBuilder, blocks);
                    usage = u;
                }
                case ApiStreamEvent.ErrorEvent(var code, var msg) -> {
                    return new TurnResult(blocks, usage, code + ": " + msg);
                }
            }
        }
        return new TurnResult(blocks, usage, null);
    }

    /**
     * Execute tool use blocks in parallel using StructuredTaskScope.
     */
    private List<ContentBlock> executeTools(List<ContentBlock> toolUses, Path cwd,
                                             SubmissionPublisher<StreamEvent> publisher) {
        List<ContentBlock> results = new ArrayList<>();

        for (ContentBlock block : toolUses) {
            if (block instanceof ContentBlock.ToolUseBlock(var id, var name, var input)) {
                BaseTool<?> tool = toolRegistry.get(name);
                publisher.submit(new StreamEvent.ToolStarted(name, id));

                if (tool == null) {
                    String errorMsg = "Unknown tool: " + name;
                    results.add(new ContentBlock.ToolResultBlock(id, errorMsg, true));
                    publisher.submit(new StreamEvent.ToolCompleted(name, id,
                            ToolResult.error(errorMsg)));
                    continue;
                }

                boolean isReadOnly = tool.isReadOnly(null);
                String filePath = extractFilePath(input);
                String command = extractCommand(input);

                PermissionDecision decision = permissionChecker.evaluate(
                        name, isReadOnly, filePath, command);

                if (decision.requiresConfirmation()) {
                    boolean approved = confirmCallback != null && confirmCallback.apply(name, decision.reason());
                    if (!approved) {
                        String msg = confirmCallback == null
                                ? decision.reason() + " (confirmation not configured)"
                                : "User denied: " + decision.reason();
                        results.add(new ContentBlock.ToolResultBlock(id, msg, true));
                        publisher.submit(new StreamEvent.ToolCompleted(name, id,
                                ToolResult.error(msg)));
                        continue;
                    }
                } else if (!decision.allowed()) {
                    results.add(new ContentBlock.ToolResultBlock(id, decision.reason(), true));
                    publisher.submit(new StreamEvent.ToolCompleted(name, id,
                            ToolResult.error(decision.reason())));
                    continue;
                }

                try {
                    ToolResult result = executeSingleTool(tool, input, cwd);
                    results.add(new ContentBlock.ToolResultBlock(id,
                            result.isError() ? result.content() : result.content(), result.isError()));
                    publisher.submit(new StreamEvent.ToolCompleted(name, id, result));
                    if (toolCarryover != null && !result.isError()) {
                        toolCarryover.evaluate(name, result);
                    }
                } catch (Exception e) {
                    String errorMsg = "Tool " + name + " failed: " + e.getMessage();
                    results.add(new ContentBlock.ToolResultBlock(id, errorMsg, true));
                    publisher.submit(new StreamEvent.ToolCompleted(name, id,
                            ToolResult.error(errorMsg)));
                }
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private ToolResult executeSingleTool(BaseTool<?> tool, JsonNode input,
                                          Path cwd) {
        ToolExecutionContext ctx = new ToolExecutionContext(cwd);
        try {
            Object parsedInput;
            if (tool.inputType() == Void.class) {
                parsedInput = null;
            } else if (input != null) {
                parsedInput = OpenHarnessObjectMapper.get().treeToValue(input, tool.inputType());
            } else {
                parsedInput = null;
            }
            return ((BaseTool<Object>) tool).execute(parsedInput, ctx);
        } catch (Exception e) {
            return ToolResult.error("Failed to parse tool input for '" + tool.name()
                    + "': " + e.getMessage());
        }
    }

    private List<ToolDefinition> buildToolDefinitions(List<String> allowedTools) {
        var tools = toolRegistry.listTools().stream();
        if (allowedTools != null && !allowedTools.isEmpty()) {
            tools = tools.filter(t -> allowedTools.contains(t.name()));
        }
        return tools
                .map(tool -> {
                    JsonNode schema = tool.inputSchema();
                    Map<String, JsonNode> schemaMap = new HashMap<>();
                    if (schema instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                        obj.fields().forEachRemaining(e -> schemaMap.put(e.getKey(), e.getValue()));
                    }
                    return new ToolDefinition(tool.name(), tool.description(), schemaMap);
                })
                .toList();
    }

    private static void flushText(StringBuilder textBuilder, List<ContentBlock> blocks) {
        if (!textBuilder.isEmpty()) {
            blocks.add(new ContentBlock.TextBlock(textBuilder.toString()));
            textBuilder.setLength(0);
        }
    }

    private void extractMemories(List<ConversationMessage> conversation) {
        if (afterTurnCallback != null) {
            try {
                afterTurnCallback.accept(conversation);
            } catch (Exception e) {
                // Silent — memory extraction is best-effort
            }
        }
    }

    private static String extractFilePath(JsonNode input) {
        if (input == null) return null;
        for (String key : List.of("file_path", "path", "filePath")) {
            var node = input.get(key);
            if (node != null && node.isTextual()) return node.asText();
        }
        return null;
    }

    private static String extractCommand(JsonNode input) {
        if (input == null) return null;
        var node = input.get("command");
        if (node != null && node.isTextual()) return node.asText();
        return null;
    }

    private record TurnResult(List<ContentBlock> blocks, UsageSnapshot usage, String error) {}
}
