package com.openharness.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.engine.tool.ToolRegistry;
import com.openharness.common.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ToolRegistry and tool execution pipeline.
 */
class ToolRegistryIT {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    void registerAndExecuteTool() {
        var tool = new BaseTool<JsonNode>("test_tool",
                "A test tool for integration testing", JsonNode.class) {
            @Override
            public ToolResult execute(JsonNode arguments, ToolExecutionContext ctx) {
                String msg = arguments.get("message").asText();
                return ToolResult.success("executed: " + msg);
            }
        };

        registry.register(tool);
        assertNotNull(registry.get("test_tool"));

        var args = OpenHarnessObjectMapper.get().createObjectNode()
                .put("message", "hello");
        var result = tool.execute(args,
                new ToolExecutionContext(Path.of("/tmp")));
        assertTrue(result.content().contains("hello"));
        assertFalse(result.isError());
    }

    @Test
    void getUnknownToolReturnsNull() {
        assertNull(registry.get("nonexistent_tool"));
    }

    @Test
    void listRegisteredTools() {
        registry.register(new BaseTool<JsonNode>("tool_a", "Tool A", JsonNode.class) {
            @Override
            public ToolResult execute(JsonNode input, ToolExecutionContext ctx) {
                return ToolResult.success("ok");
            }
        });
        registry.register(new BaseTool<JsonNode>("tool_b", "Tool B", JsonNode.class) {
            @Override
            public ToolResult execute(JsonNode input, ToolExecutionContext ctx) {
                return ToolResult.success("ok");
            }
        });

        var tools = registry.listTools();
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("tool_a")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("tool_b")));
    }

    @Test
    void toolExecutionContextCarriesCwdAndMetadata() {
        var ctx = new ToolExecutionContext(Path.of("/workspace"),
                Map.of("key", (Object) "value"));

        assertEquals(Path.of("/workspace"), ctx.cwd());
        assertEquals("value", ctx.metadata().get("key"));
    }

    @Test
    void toolResultError() {
        var result = ToolResult.error("something went wrong");
        assertTrue(result.isError());
        assertEquals("something went wrong", result.content());
        assertTrue(result.mediaFiles().isEmpty());
    }

    @Test
    void toApiSchemaProducesValidJson() {
        registry.register(new BaseTool<JsonNode>("test_tool", "Description", JsonNode.class) {
            @Override
            public ToolResult execute(JsonNode input, ToolExecutionContext ctx) {
                return ToolResult.success("ok");
            }
        });

        var schemas = registry.toApiSchema();
        assertEquals(1, schemas.size());
        assertEquals("test_tool", schemas.getFirst().get("name").asText());
        assertEquals("Description", schemas.getFirst().get("description").asText());
        assertTrue(schemas.getFirst().has("input_schema"));
    }
}
