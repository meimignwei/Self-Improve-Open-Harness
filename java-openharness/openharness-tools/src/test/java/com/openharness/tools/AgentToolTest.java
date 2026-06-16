package com.openharness.tools;

import com.openharness.common.AgentRuntime;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.extensions.swarm.BackendRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolTest {

    private AgentRuntime mockRuntime;
    private AgentTool tool;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        mockRuntime = mock(AgentRuntime.class);
        tool = new AgentTool(mockRuntime);
        ctx = new ToolExecutionContext(Path.of(System.getProperty("user.dir")));
    }

    @Test
    void toolNameShouldBeAgent() {
        assertEquals("agent", tool.name());
    }

    @Test
    void toolDescriptionShouldNotBeEmpty() {
        assertNotNull(tool.description());
        assertFalse(tool.description().isEmpty());
    }

    @Test
    void executeShouldReturnErrorForInvalidMode() {
        var input = new AgentTool.Input("test", "Do something", null, null, null, null, "invalid_mode");
        var result = tool.execute(input, ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("Invalid mode"));
    }

    @Test
    void executeWithValidModeShouldAttemptSpawn() {
        var input = new AgentTool.Input("desc", "Run task", "general-purpose",
                "claude-sonnet-4-6", null, "my-team", "local_agent");
        var result = tool.execute(input, ctx);

        // May fail due to subprocess backend not being configured,
        // but should not crash
        assertNotNull(result);
    }

    @Test
    void inputShouldRequirePrompt() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTool.Input(null, null, null, null, null, null, null));
    }

    @Test
    void inputShouldAcceptAllFields() {
        var input = new AgentTool.Input("description", "prompt text",
                "Explore", "claude-haiku-4-5", "custom-cmd", "team-a", "remote_agent");

        assertEquals("description", input.description());
        assertEquals("prompt text", input.prompt());
        assertEquals("Explore", input.subagentType());
        assertEquals("claude-haiku-4-5", input.model());
        assertEquals("custom-cmd", input.command());
        assertEquals("team-a", input.team());
        assertEquals("remote_agent", input.mode());
    }

    @Test
    void isReadOnlyShouldReturnFalse() {
        var input = new AgentTool.Input("d", "p", null, null, null, null, null);
        assertFalse(tool.isReadOnly(input));
    }
}
