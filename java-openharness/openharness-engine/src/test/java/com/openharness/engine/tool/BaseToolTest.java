package com.openharness.engine.tool;

import com.openharness.common.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BaseToolTest {

    @Test
    void shouldReturnConfiguredNameAndDescription() {
        var tool = new TestTool("my_tool", "Does something", String.class);
        assertEquals("my_tool", tool.name());
        assertEquals("Does something", tool.description());
        assertEquals(String.class, tool.inputType());
    }

    @Test
    void isReadOnlyShouldDefaultToFalse() {
        var tool = new TestTool("t", "d", Void.class);
        assertFalse(tool.isReadOnly(null));
    }

    @Test
    void toApiSchemaShouldReturnJsonWithNameAndDescription() {
        var tool = new TestTool("read_file", "Read a file from disk", String.class);
        var schema = tool.toApiSchema();
        assertEquals("read_file", schema.get("name").asText());
        assertEquals("Read a file from disk", schema.get("description").asText());
        assertTrue(schema.has("input_schema"));
    }

    @Test
    void contextShouldStoreCwdAndMetadata() {
        var ctx = new ToolExecutionContext(Path.of("/tmp"));
        assertEquals(Path.of("/tmp"), ctx.cwd());
        assertTrue(ctx.metadata().isEmpty());
    }

    @Test
    void contextShouldAcceptMetadata() {
        var meta = new java.util.HashMap<String, Object>();
        meta.put("plan_mode", true);
        var ctx = new ToolExecutionContext(Path.of("."), meta);
        assertEquals(Boolean.TRUE, ctx.metadata().get("plan_mode"));
    }

    @Test
    void contextShouldAllowMetadataMutation() {
        var ctx = new ToolExecutionContext(Path.of("."));
        ctx.metadata().put("key", "value");
        assertEquals("value", ctx.metadata().get("key"));
    }

    // Concrete tool for testing
    @SuppressWarnings("unchecked")
    static <T> TestTool create(String name, String description, Class<T> inputType) {
        return new TestTool(name, description, inputType);
    }

    static class TestTool extends BaseTool<Void> {
        TestTool(String name, String description, Class<?> inputType) {
            super(name, description, (Class<Void>) inputType);
        }

        @Override
        public ToolResult execute(Void arguments, ToolExecutionContext context) {
            return ToolResult.success("ok");
        }
    }
}
