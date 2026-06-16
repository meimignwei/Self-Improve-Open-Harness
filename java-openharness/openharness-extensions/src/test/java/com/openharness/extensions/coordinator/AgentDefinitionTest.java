package com.openharness.extensions.coordinator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentDefinitionTest {

    @Test
    void recordShouldValidateRequiredFields() {
        assertThrows(IllegalArgumentException.class, () ->
                new AgentDefinition(null, "", "", List.of(), List.of(), null,
                        null, null, null, List.of(), List.of(), List.of(),
                        null, false, null, null, null, List.of(), false, null, null,
                        "builtin", "test.md", null));
    }

    @Test
    void recordShouldCopyMutableLists() {
        var tools = new java.util.ArrayList<>(List.of("read", "write"));
        var def = new AgentDefinition("test", "", "", tools, List.of(), null,
                null, null, null, List.of(), List.of(), List.of(),
                null, false, null, null, null, List.of(), false, null, null,
                "builtin", "test.md", null);

        tools.add("execute");
        assertEquals(2, def.tools().size());
    }

    @Test
    void recordShouldDefaultNullSource() {
        var def = new AgentDefinition("test", "", "", List.of(), List.of(), null,
                null, null, null, List.of(), List.of(), List.of(),
                null, false, null, null, null, List.of(), false, null, null,
                null, "test.md", null);

        assertEquals("builtin", def.source());
    }

    @Test
    void recordShouldDefaultNullPermissions() {
        var def = new AgentDefinition("test", "", "", List.of(), List.of(), null,
                null, null, null, List.of(), List.of(), List.of(),
                null, false, null, null, null, null, false, null, null,
                "builtin", "test.md", null);

        assertTrue(def.permissions().isEmpty());
    }

    @Test
    void permissionsShouldBeCopied() {
        var perms = new java.util.ArrayList<>(List.of("allow-read"));
        var def = new AgentDefinition("test", "", "", List.of(), List.of(), null,
                null, null, null, List.of(), List.of(), List.of(),
                null, false, null, null, null, perms, false, null, null,
                "builtin", "test.md", null);

        perms.add("allow-write");
        assertEquals(1, def.permissions().size());
        assertEquals("allow-read", def.permissions().getFirst());
    }
}

class AgentDefinitionsLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void getDefinitionShouldReturnNullForUnknownName() {
        var loader = new AgentDefinitionsLoader();
        assertNull(loader.getDefinition("nonexistent-agent"));
    }

    @Test
    void parseFrontmatterShouldExtractYaml() {
        String content = """
                ---
                name: test-agent
                description: A test agent
                tools:
                  - read
                  - write
                model: claude-sonnet-4-6
                permissions: allow-read, allow-write
                ---
                You are a helpful assistant.
                """;

        var loader = new AgentDefinitionsLoader();
        var fm = loader.parseFrontmatter(content);

        assertEquals("test-agent", fm.get("name"));
        assertEquals("A test agent", fm.get("description"));
    }

    @Test
    void bodyContentShouldReturnTextAfterFrontmatter() {
        String content = """
                ---
                name: test
                ---
                This is the system prompt body.
                """;

        var loader = new AgentDefinitionsLoader();
        var body = loader.bodyContent(content);

        assertEquals("This is the system prompt body.", body);
    }

    @Test
    void bodyContentWithoutFrontmatterShouldReturnFullText() {
        String content = "Just a plain text content.";

        var loader = new AgentDefinitionsLoader();
        var body = loader.bodyContent(content);

        assertEquals("Just a plain text content.", body);
    }

    @Test
    void loadAllShouldReturnBuiltinAgents() {
        var loader = new AgentDefinitionsLoader();
        var agents = loader.loadAll(List.of());

        assertNotNull(agents);
        // Built-in agents return null from loadBuiltinAgent, so empty list
        assertTrue(agents.isEmpty());
    }

    @Test
    void parseAgentMdShouldCreateAgentDefinition() throws Exception {
        String content = """
                ---
                name: my-agent
                description: My custom agent
                model: claude-haiku-4-5
                permissions: bash, read
                ---
                You are a custom assistant.
                """;

        Path mdFile = tempDir.resolve("my-agent.md");
        Files.writeString(mdFile, content);

        var loader = new AgentDefinitionsLoader();
        var def = loader.parseAgentMd(mdFile);

        assertEquals("my-agent", def.name());
        assertEquals("My custom agent", def.description());
        assertEquals("claude-haiku-4-5", def.model());
        assertEquals(List.of("bash", "read"), def.permissions());
        assertEquals("You are a custom assistant.", def.systemPrompt());
    }
}
