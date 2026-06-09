package io.openharness.core;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.SkillUsageMiddleware;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

        String provider = settings.getProvider() != null ? settings.getProvider() : "anthropic";
        String modelName = ctx.getModel() != null ? ctx.getModel() : settings.getModel();

        Model model = switch (provider.toLowerCase()) {
            case "openai" -> {
                var builder = OpenAIChatModel.builder()
                        .apiKey(settings.getApiKey())
                        .modelName(modelName);
                if (settings.getBaseUrl() != null && !settings.getBaseUrl().isBlank()) {
                    builder.baseUrl(settings.getBaseUrl());
                }
                yield builder.build();
            }
            case "anthropic" -> AnthropicChatModel.builder()
                    .apiKey(settings.getApiKey())
                    .modelName(modelName)
                    .build();
            default -> {
                log.warn("Unknown provider '{}', falling back to anthropic", provider);
                yield AnthropicChatModel.builder()
                        .apiKey(settings.getApiKey())
                        .modelName(modelName)
                        .build();
            }
        };

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WebSearchTool());
        toolkit.registerTool(new WebFetchTool());
        toolkit.registerAgentTool(new AskUserQuestionTool());

        List<MiddlewareBase> middlewares = new ArrayList<>();
        middlewares.add(new SystemPromptAssembler(ctx.getWorkspaceDir(), settings));
        middlewares.add(new CostTrackingMiddleware());
        middlewares.add(new SessionPersistenceMiddleware(writer, sessionFactory));

        // ── Skills ──
        List<AgentSkillRepository> skillRepos = new ArrayList<>();

        if (!Boolean.FALSE.equals(settings.isSkillEnabled())) {
            Path workspacePath = ctx.getWorkspaceDir();
            AbstractFilesystem fs = new LocalFilesystem(workspacePath);

            Supplier<RuntimeContext> ctxSupplier = () ->
                    RuntimeContext.builder().sessionId(ctx.getSessionId()).build();

            WorkspaceSkillRepository workspaceSkillRepo = new WorkspaceSkillRepository(
                    fs, ".claude/skills", ctxSupplier);
            skillRepos.add(workspaceSkillRepo);

            SkillUsageStore usageStore = new SkillUsageStore(fs);
            middlewares.add(new SkillUsageMiddleware(usageStore));

            // 全局 skills 目录 (~/.claude/skills)
            Path globalSkills = Path.of(System.getProperty("user.home"), ".claude", "skills");
            if (Files.isDirectory(globalSkills)) {
                AbstractFilesystem globalFs = new LocalFilesystem(
                        globalSkills.getParent());
                WorkspaceSkillRepository globalRepo = new WorkspaceSkillRepository(
                        globalFs, "skills", ctxSupplier);
                skillRepos.add(globalRepo);
            }

            log.info("Skills enabled: {} workspace repos", skillRepos.size());
        }

        // ── MCP ──
        Path mcpConfigPath = ctx.getWorkspaceDir().resolve(".oh/mcp.json");
        if (Files.exists(mcpConfigPath)) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, McpServerConfig> mcpServers = mapper.readValue(
                        mcpConfigPath.toFile(),
                        mapper.getTypeFactory().constructMapType(
                                Map.class, String.class, McpServerConfig.class));
                McpServerRegistrar.register(toolkit, mcpServers);
                log.info("MCP servers registered: {}", mcpServers.keySet());
            } catch (Exception e) {
                log.warn("Failed to load MCP config from {}: {}", mcpConfigPath, e.getMessage());
            }
        }

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
                .skillRepositories(skillRepos)
                .enableSkillManageTool(SkillManageConfig.defaults())
                .enablePlanMode()
                .build();

        log.info("Agent assembled: model={}, tools={}, skills={}, middlewares={}",
                model.getModelName(), toolkit.getToolNames().size(),
                skillRepos.size(), middlewares.size());

        return agent;
    }
}
