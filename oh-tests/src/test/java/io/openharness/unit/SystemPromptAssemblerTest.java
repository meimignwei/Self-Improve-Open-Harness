package io.openharness.unit;

import io.agentscope.core.agent.RuntimeContext;
import io.openharness.core.config.Settings;
import io.openharness.core.middleware.SystemPromptAssembler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptAssemblerTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldInjectAllContextWhenFilesPresent() throws Exception {
        Files.writeString(workspaceRoot.resolve("AGENTS.md"), "You are a helpful assistant.");
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), "User prefers Java 17.");
        Path skillsDir = workspaceRoot.resolve(".claude/skills");
        Files.createDirectories(skillsDir.resolve("testing"));
        Files.writeString(skillsDir.resolve("testing/SKILL.md"), "Test skill description\nmore");

        Settings settings = Settings.defaults();
        settings.setAllowedPaths(List.of("/tmp", "/home"));

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("test-session")
                .build();

        SystemPromptAssembler assembler = new SystemPromptAssembler(workspaceRoot, settings);
        String prompt = assembler.onSystemPrompt(null, ctx, "Default sys prompt").block();

        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("Default sys prompt");
        assertThat(prompt).contains("AGENTS.md");
        assertThat(prompt).contains("You are a helpful assistant.");
        assertThat(prompt).contains("MEMORY.md");
        assertThat(prompt).contains("User prefers Java 17.");
        assertThat(prompt).contains("Available Skills");
        assertThat(prompt).contains("testing: Test skill description");
        assertThat(prompt).contains("Permission Policy");
        assertThat(prompt).contains("/tmp");
        assertThat(prompt).contains("/home");
    }

    @Test
    void shouldNotFailWhenOptionalFilesMissing() {
        Settings settings = Settings.defaults();
        RuntimeContext ctx = RuntimeContext.builder().sessionId("test-session").build();
        SystemPromptAssembler assembler = new SystemPromptAssembler(workspaceRoot, settings);
        String prompt = assembler.onSystemPrompt(null, ctx, null).block();

        assertThat(prompt).isNotNull();
    }

    @Test
    void shouldTruncateMemoryWhenExceedsTokenBudget() throws Exception {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 50000; i++) {
            large.append("word ");
        }
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), large.toString());

        Settings settings = Settings.defaults();
        settings.setMaxContextTokens(10);

        RuntimeContext ctx = RuntimeContext.builder().sessionId("test-session").build();
        String prompt = new SystemPromptAssembler(workspaceRoot, settings)
                .onSystemPrompt(null, ctx, null).block();

        assertThat(prompt).contains("truncated to fit token budget");
        assertThat(prompt.length()).isLessThan(large.length());
    }

    @Test
    void shouldNotTruncateWhenUnderBudget() throws Exception {
        String content = "Short memory content";
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), content);

        Settings settings = Settings.defaults();
        settings.setMaxContextTokens(100_000);

        RuntimeContext ctx = RuntimeContext.builder().sessionId("test-session").build();
        String prompt = new SystemPromptAssembler(workspaceRoot, settings)
                .onSystemPrompt(null, ctx, null).block();

        assertThat(prompt).contains(content);
        assertThat(prompt).doesNotContain("truncated");
    }

    @Test
    void shouldOmitPermissionWhenAllowedPathsIsEmpty() {
        Settings settings = Settings.defaults();
        settings.setAllowedPaths(List.of());

        RuntimeContext ctx = RuntimeContext.builder().sessionId("test-session").build();
        String prompt = new SystemPromptAssembler(workspaceRoot, settings)
                .onSystemPrompt(null, ctx, null).block();

        assertThat(prompt).doesNotContain("Permission Policy");
    }

    @Test
    void shouldOmitSkillsWhenDirectoryNotFound() {
        Settings settings = Settings.defaults();
        RuntimeContext ctx = RuntimeContext.builder().sessionId("test-session").build();
        String prompt = new SystemPromptAssembler(workspaceRoot, settings)
                .onSystemPrompt(null, ctx, null).block();

        assertThat(prompt).doesNotContain("Available Skills");
    }
}
