package com.openharness.tools;

import com.openharness.common.AgentRuntime;
import com.openharness.common.CronJobRegistry;
import com.openharness.common.McpClient;
import com.openharness.common.TeamRegistry;
import com.openharness.engine.task.TaskManager;
import com.openharness.engine.tool.ToolRegistry;

import java.nio.file.Path;

/**
 * Bootstraps and registers all built-in OpenHarness tools.
 * Call {@link #createBasicRegistry()} for a zero-dependency setup,
 * or use {@link #createDefaultRegistry} to inject required services.
 */
public final class ToolBootstrap {

    private ToolBootstrap() {}

    /**
     * Creates a registry with all tools that have no external dependencies.
     * Tools requiring TaskManager, TeamRegistry, MCP, AgentRuntime, etc. are omitted.
     */
    public static ToolRegistry createBasicRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registerFileTools(registry);
        registerSearchTools(registry);
        registerWebTools(registry);
        registerNotebookTools(registry);
        registerPlanModeTools(registry);
        registerUtilityTools(registry);
        registerConfigTools(registry);
        registerCronTools(registry);
        registerWorktreeTools(registry);
        registerImageTools(registry);
        registerLspTools(registry);
        return registry;
    }

    /**
     * Creates a fully populated registry with all available tools.
     */
    public static ToolRegistry createDefaultRegistry(TaskManager taskManager,
                                                      TeamRegistry teamRegistry,
                                                      CronJobRegistry cronRegistry,
                                                      McpClient mcpClient,
                                                      Path configPath,
                                                      AgentRuntime agentRuntime,
                                                      SkillTool.SkillInvoker skillInvoker,
                                                      SendMessageTool.MessageSender messageSender) {
        ToolRegistry registry = createBasicRegistry();

        if (taskManager != null) {
            registry.register(new TaskTools.TaskCreateTool(taskManager));
            registry.register(new TaskTools.TaskListTool(taskManager));
            registry.register(new TaskTools.TaskGetTool(taskManager));
            registry.register(new TaskTools.TaskOutputTool(taskManager));
            registry.register(new TaskTools.TaskStopTool(taskManager));
            registry.register(new TaskTools.TaskUpdateTool(taskManager));
        }

        if (teamRegistry != null) {
            registry.register(new TeamTools.TeamCreateTool(teamRegistry));
            registry.register(new TeamTools.TeamDeleteTool(teamRegistry));
        }

        if (cronRegistry != null) {
            registry.register(new RemoteTriggerTool(cronRegistry));
        }

        if (mcpClient != null) {
            registry.register(new McpTools.ListMcpResourcesTool(mcpClient));
            registry.register(new McpTools.ReadMcpResourceTool(mcpClient));
            if (configPath != null) {
                registry.register(new McpAuthTool(mcpClient, configPath));
            }
        }

        if (agentRuntime != null) {
            registry.register(new AgentTool(agentRuntime));
        }

        if (skillInvoker != null) {
            registry.register(new SkillTool(skillInvoker));
        }

        if (messageSender != null) {
            registry.register(new SendMessageTool(messageSender));
        }

        // ToolSearchTool needs the registry itself — register last
        registry.register(new ToolSearchTool(registry));

        return registry;
    }

    public static void registerFileTools(ToolRegistry registry) {
        registry.register(new FileReadTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
    }

    public static void registerSearchTools(ToolRegistry registry) {
        registry.register(new GrepTool());
        registry.register(new GlobTool());
        registry.register(new BashTool());
    }

    public static void registerWebTools(ToolRegistry registry) {
        registry.register(new WebFetchTool());
        registry.register(new WebSearchTool());
    }

    public static void registerNotebookTools(ToolRegistry registry) {
        registry.register(new NotebookEditTool());
    }

    public static void registerPlanModeTools(ToolRegistry registry) {
        registry.register(new PlanModeTools.EnterPlanModeTool());
        registry.register(new PlanModeTools.ExitPlanModeTool());
    }

    public static void registerUtilityTools(ToolRegistry registry) {
        registry.register(new BriefTool());
        registry.register(new SleepTool());
        registry.register(new TodoWriteTool());
        registry.register(new AskUserQuestionTool());
    }

    public static void registerConfigTools(ToolRegistry registry) {
        registry.register(new ConfigTool());
    }

    public static void registerCronTools(ToolRegistry registry) {
        registry.register(new CronTools.CronCreateTool());
        registry.register(new CronTools.CronDeleteTool());
        registry.register(new CronTools.CronListTool());
        registry.register(new CronTools.CronToggleTool());
    }

    public static void registerWorktreeTools(ToolRegistry registry) {
        registry.register(new WorktreeTools.EnterWorktreeTool());
        registry.register(new WorktreeTools.ExitWorktreeTool());
    }

    public static void registerImageTools(ToolRegistry registry) {
        registry.register(new ImageToTextTool());
        registry.register(new ImageGenerationTool());
    }

    public static void registerLspTools(ToolRegistry registry) {
        registry.register(new LspTool());
    }
}
