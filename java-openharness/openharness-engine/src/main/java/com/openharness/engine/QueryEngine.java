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

    public QueryEngine(StreamingApiClient apiClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker) {
        this.apiClient = apiClient;
        this.toolRegistry = toolRegistry;
        this.permissionChecker = permissionChecker;
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

    private void executeLoop(List<ConversationMessage> messages, QueryOptions options,
                              SubmissionPublisher<StreamEvent> publisher) {
        int maxTurns = options.maxTurns().orElse(10);
        String model = options.model().orElse("claude-sonnet-4-6");
        String systemPrompt = options.systemPrompt().orElse(null);
        Path cwd = options.workingDirectory()
                .map(Path::of)
                .orElse(Path.of("").toAbsolutePath());

        List<ConversationMessage> conversation = new ArrayList<>(messages);
        List<ToolDefinition> toolDefs = buildToolDefinitions();

        for (int turn = 0; turn < maxTurns; turn++) {
            StreamOptions streamOpts = StreamOptions.defaults()
                    .withSystemPrompt(systemPrompt);

            Stream<ApiStreamEvent> apiStream = apiClient.streamMessages(
                    model, systemPrompt, conversation, toolDefs, streamOpts);

            TurnResult result = processApiStream(apiStream, publisher);

            if (result.error() != null) {
                publisher.submit(new StreamEvent.ErrorStreamEvent(result.error()));
                return;
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
                return;
            }

            // Add assistant response to conversation
            conversation.add(new ConversationMessage(Role.ASSISTANT, responseBlocks));

            // Execute tools in parallel
            List<ContentBlock> toolResults = executeTools(toolUses, cwd, publisher);
            conversation.add(new ConversationMessage(Role.USER, toolResults));

            publisher.submit(new StreamEvent.StatusEvent(
                    "Turn " + (turn + 1) + " complete, " + toolUses.size() + " tool(s) executed",
                    StreamEvent.StatusLevel.INFO));
        }

        publisher.submit(new StreamEvent.ErrorStreamEvent(
                "Max turns (" + maxTurns + ") exceeded"));
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

                // Permission check
                boolean isReadOnly = tool.isReadOnly(null);
                String filePath = extractFilePath(input);
                String command = extractCommand(input);

                PermissionDecision decision = permissionChecker.evaluate(
                        name, isReadOnly, filePath, command);

                if (!decision.allowed()) {
                    if (decision.requiresConfirmation()) {
                        // In DEFAULT mode, confirm is treated as deny for now
                        results.add(new ContentBlock.ToolResultBlock(id,
                                decision.reason(), true));
                        publisher.submit(new StreamEvent.ToolCompleted(name, id,
                                ToolResult.error(decision.reason())));
                    } else {
                        results.add(new ContentBlock.ToolResultBlock(id,
                                decision.reason(), true));
                        publisher.submit(new StreamEvent.ToolCompleted(name, id,
                                ToolResult.error(decision.reason())));
                    }
                    continue;
                }

                // Execute tool
                try {
                    ToolResult result = executeSingleTool(tool, input, cwd);
                    results.add(new ContentBlock.ToolResultBlock(id,
                            result.isError() ? result.content() : result.content(), result.isError()));
                    publisher.submit(new StreamEvent.ToolCompleted(name, id, result));
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
        // Use the tool's input type — since we don't have deserialization yet,
        // pass null for the typed input and let tools handle it
        return ((BaseTool<Object>) tool).execute(null, ctx);
    }

    private List<ToolDefinition> buildToolDefinitions() {
        var mapper = OpenHarnessObjectMapper.get();
        return toolRegistry.listTools().stream()
                .map(tool -> {
                    Map<String, JsonNode> schema = Map.of(
                            "type", mapper.getNodeFactory().textNode("object")
                    );
                    return new ToolDefinition(tool.name(), tool.description(), schema);
                })
                .toList();
    }

    private static void flushText(StringBuilder textBuilder, List<ContentBlock> blocks) {
        if (!textBuilder.isEmpty()) {
            blocks.add(new ContentBlock.TextBlock(textBuilder.toString()));
            textBuilder.setLength(0);
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
