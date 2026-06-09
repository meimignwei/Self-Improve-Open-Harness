package io.openharness.unit;

import io.agentscope.harness.HarnessAgent;
import io.openharness.core.AgentAssembler;
import io.openharness.core.config.Settings;
import io.openharness.core.session.SessionContext;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentAssemblerTest {

    @TempDir
    Path workspaceDir;

    private Settings settings;
    private SessionContext ctx;
    private AgentAssembler assembler;

    @BeforeEach
    void setUp() {
        settings = Settings.defaults();
        settings.setApiKey("test-key");
        settings.setModel("claude-sonnet-4-6");

        ctx = SessionContext.builder()
                .sessionId("test-session")
                .workspaceDir(workspaceDir)
                .settings(settings)
                .model("claude-sonnet-4-6")
                .build();

        SqlSessionFactory ssf = mock(SqlSessionFactory.class);
        assembler = new AgentAssembler(ssf);
    }

    @Test
    void shouldAssembleFullAgentWithAllMiddlewareAndTools() {
        HarnessAgent agent = assembler.assemble(ctx);

        assertThat(agent).isNotNull();
        assertThat(agent.getName()).isEqualTo("oh");
        assertThat(agent.getModel().getModelName()).isEqualTo("claude-sonnet-4-6");
        assertThat(agent.getWorkspace()).isEqualTo(workspaceDir);
        assertThat(agent.isPlanModeEnabled()).isTrue();
        assertThat(agent.getToolkit().size()).isGreaterThanOrEqualTo(2);
        assertThat(agent.getMiddleware()).hasSize(3);
        assertThat(agent.getCompaction().getTriggerMessages()).isEqualTo(30);
        assertThat(agent.getCompaction().getKeepMessages()).isEqualTo(10);
    }

    @Test
    void shouldOrderMiddlewareCorrectly() {
        HarnessAgent agent = assembler.assemble(ctx);

        var middleware = agent.getMiddleware();
        assertThat(middleware.get(0).getClass().getSimpleName())
                .isEqualTo("SystemPromptAssembler");
        assertThat(middleware.get(1).getClass().getSimpleName())
                .isEqualTo("CostTrackingMiddleware");
        assertThat(middleware.get(2).getClass().getSimpleName())
                .isEqualTo("SessionPersistenceMiddleware");
    }

    @Test
    void shouldRegisterCustomTools() {
        HarnessAgent agent = assembler.assemble(ctx);

        var toolkit = agent.getToolkit();
        assertThat(toolkit.getRegisteredObjects()).hasSize(2);
        assertThat(toolkit.getRegisteredAgentTools()).hasSize(1);

        assertThat(toolkit.getRegisteredObjects().stream()
                .map(o -> o.getClass().getSimpleName())
                .toList())
                .contains("WebSearchTool", "WebFetchTool");
    }

    @Test
    void shouldUseDefaultModelWhenContextModelIsNull() {
        ctx.setModel(null);
        settings.setModel("claude-haiku-4-5");

        HarnessAgent agent = assembler.assemble(ctx);

        assertThat(agent.getModel().getModelName()).isEqualTo("claude-haiku-4-5");
    }
}
