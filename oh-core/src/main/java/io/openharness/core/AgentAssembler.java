package io.openharness.core;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.openharness.core.config.Settings;
import io.openharness.core.middleware.CostTrackingMiddleware;
import io.openharness.core.middleware.SessionPersistenceMiddleware;
import io.openharness.core.middleware.SystemPromptAssembler;
import io.openharness.core.persistence.AsyncPersistenceWriter;
import io.openharness.core.session.SessionContext;
import io.openharness.core.tools.AskUserQuestionTool;
import io.openharness.core.tools.WebFetchTool;
import io.openharness.core.tools.WebSearchTool;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class AgentAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentAssembler.class);

    private final SqlSessionFactory sessionFactory;
    private final AsyncPersistenceWriter writer;

    public AgentAssembler(SqlSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
        this.writer = new AsyncPersistenceWriter(sessionFactory);
    }

    public AgentAssembler(SqlSessionFactory sessionFactory, AsyncPersistenceWriter writer) {
        this.sessionFactory = sessionFactory;
        this.writer = writer;
    }

    public AsyncPersistenceWriter getWriter() {
        return writer;
    }

    public HarnessAgent assemble(SessionContext ctx) {
        Settings settings = ctx.getSettings();

        Model model = AnthropicChatModel.builder()
                .apiKey(settings.getApiKey())
                .modelName(ctx.getModel() != null ? ctx.getModel() : settings.getModel())
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WebSearchTool());
        toolkit.registerTool(new WebFetchTool());
        toolkit.registerAgentTool(new AskUserQuestionTool());

        List<MiddlewareBase> middlewares = new ArrayList<>();
        middlewares.add(new SystemPromptAssembler(ctx.getWorkspaceDir(), settings));
        middlewares.add(new CostTrackingMiddleware());
        middlewares.add(new SessionPersistenceMiddleware(writer, sessionFactory));

        CompactionConfig compaction = CompactionConfig.builder()
                .triggerMessages(30)
                .keepMessages(10)
                .build();

        HarnessAgent agent = HarnessAgent.builder()
                .name("oh")
                .model(model)
                .workspace(ctx.getWorkspaceDir())
                .toolkit(toolkit)
                .compaction(compaction)
                .middlewares(middlewares)
                .enablePlanMode()
                .build();

        log.info("Agent assembled: model={}, tools={}, middlewares={}",
                model.getModelName(), toolkit.getToolNames().size(), middlewares.size());

        return agent;
    }
}
