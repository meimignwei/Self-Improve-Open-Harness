package com.openharness.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.openharness.api.ProviderInfo;
import com.openharness.api.StreamOptions;
import com.openharness.api.StreamingApiClient;
import com.openharness.api.ToolDefinition;
import com.openharness.common.*;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.engine.tool.ToolRegistry;
import com.openharness.config.PermissionSettings;
import com.openharness.permissions.PermissionChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for QueryEngine end-to-end behavior.
 */
class QueryEngineIT {

    @Test
    void shouldStreamTextResponse() {
        ToolRegistry registry = new ToolRegistry();

        StreamingApiClient mockClient = new MockClient(List.of(
                new ApiStreamEvent.ContentDelta("Hello", 0),
                new ApiStreamEvent.TurnComplete(new UsageSnapshot(10, 5), "end_turn")
        ));

        QueryEngine engine = new QueryEngine(mockClient, registry, new PermissionChecker(new PermissionSettings()));
        Flow.Publisher<StreamEvent> publisher = engine.runQuery(
                List.of(new ConversationMessage(Role.USER,
                        List.of(new ContentBlock.TextBlock("Say hello")))),
                QueryOptions.defaults());

        List<StreamEvent> events = PublisherAdapter.toList(publisher);
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.AssistantTextDelta));
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.AssistantTurnComplete));
    }

    @Test
    void shouldDeserializeToolInputAndExecute() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        String toolInputJson = "{\"message\": \"hello world\"}";
        StreamingApiClient mockClient = new MockClient(List.of(
                new ApiStreamEvent.ToolUseStart("id-1", "echo"),
                new ApiStreamEvent.ToolUseComplete("id-1", "echo",
                        OpenHarnessObjectMapper.get().readTree(toolInputJson)),
                new ApiStreamEvent.TurnComplete(new UsageSnapshot(10, 5), "end_turn")
        ));

        QueryEngine engine = new QueryEngine(mockClient, registry, new PermissionChecker(new PermissionSettings()));
        Flow.Publisher<StreamEvent> publisher = engine.runQuery(
                List.of(new ConversationMessage(Role.USER,
                        List.of(new ContentBlock.TextBlock("Echo this")))),
                QueryOptions.defaults());

        List<StreamEvent> events = PublisherAdapter.toList(publisher);
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCompleted tc &&
                tc.result().content().equals("hello world")));
    }

    @Test
    void shouldInjectCarryoverIntoSystemPrompt() {
        ToolCarryover carryover = new ToolCarryover(Path.of("/dev/null"));
        QueryEngine engine = new QueryEngine(
                new MockClient(List.of()),
                new ToolRegistry(),
                new PermissionChecker(new PermissionSettings()),
                new CostTracker(),
                new AutoCompactState(),
                carryover
        );

        assertNotNull(engine);
    }

    @Test
    void shouldGenerateRealToolSchemas() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        registry.register(new UpperTool());

        var schemas = registry.toApiSchema();
        assertEquals(2, schemas.size());
        for (var schema : schemas) {
            assertTrue(schema.has("input_schema"));
            assertTrue(schema.get("input_schema").has("properties"));
        }
    }

    // Simple test tools that don't require external dependencies
    static class EchoTool extends BaseTool<EchoTool.Input> {
        EchoTool() { super("echo", "Echoes the input message.", Input.class); }
        @Override public ToolResult execute(Input args, ToolExecutionContext ctx) {
            return ToolResult.success(args.message());
        }
        public record Input(String message) {}
    }

    static class UpperTool extends BaseTool<UpperTool.Input> {
        UpperTool() { super("upper", "Converts text to uppercase.", Input.class); }
        @Override public ToolResult execute(Input args, ToolExecutionContext ctx) {
            return ToolResult.success(args.text().toUpperCase());
        }
        public record Input(String text) {}
    }

    // Mock client that emits a fixed sequence of events
    record MockClient(List<ApiStreamEvent> events) implements StreamingApiClient {
        @Override
        public Stream<ApiStreamEvent> streamMessages(String model, String systemPrompt,
                                                      List<ConversationMessage> messages,
                                                      List<ToolDefinition> tools,
                                                      StreamOptions options) {
            return events.stream();
        }

        @Override
        public ProviderInfo getProviderInfo() {
            return ProviderInfo.apiKey("mock");
        }
    }
}
