package io.openharness.unit;

import io.agentscope.harness.agent.HarnessAgent;
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
        assertThat(agent.getWorkspaceManager().getWorkspace()).isEqualTo(workspaceDir);
        assertThat(agent.getToolkit().getToolNames()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldRegisterCustomTools() {
        HarnessAgent agent = assembler.assemble(ctx);

        var toolNames = agent.getToolkit().getToolNames();
        assertThat(toolNames).contains("web_search", "web_fetch", "ask_user_question");
    }

    @Test
    void shouldUseDefaultModelWhenContextModelIsNull() {
        ctx.setModel(null);
        settings.setModel("claude-haiku-4-5");

        HarnessAgent agent = assembler.assemble(ctx);

        assertThat(agent.getModel().getModelName()).isEqualTo("claude-haiku-4-5");
    }
}
