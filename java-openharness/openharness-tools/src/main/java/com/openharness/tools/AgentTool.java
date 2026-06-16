package com.openharness.tools;

import com.openharness.common.AgentRuntime;
import com.openharness.common.ConversationMessage;
import com.openharness.common.ContentBlock;
import com.openharness.common.QueryOptions;
import com.openharness.common.Role;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import com.openharness.extensions.coordinator.AgentDefinition;
import com.openharness.extensions.coordinator.AgentDefinitionsLoader;
import com.openharness.extensions.swarm.BackendRegistry;
import com.openharness.extensions.swarm.InProcessBackend;
import com.openharness.extensions.swarm.SpawnResult;
import com.openharness.extensions.swarm.TeammateBackend;
import com.openharness.extensions.swarm.TeammateSpec;
import com.openharness.extensions.coordinator.CoordinatorMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Spawn a sub-agent to handle a task autonomously via the swarm backend.
 * Java equivalent of Python tools/agent_tool.py.
 */
public class AgentTool extends BaseTool<AgentTool.Input> {

    private static final Logger logger = LoggerFactory.getLogger(AgentTool.class);

    private final AgentRuntime agentRuntime;
    private final AgentDefinitionsLoader definitionsLoader;

    public AgentTool(AgentRuntime agentRuntime) {
        super("agent", "Spawn a local background agent task.", Input.class);
        this.agentRuntime = agentRuntime;
        this.definitionsLoader = new AgentDefinitionsLoader();
    }

    public AgentTool(AgentRuntime agentRuntime, AgentDefinitionsLoader definitionsLoader) {
        super("agent", "Spawn a local background agent task.", Input.class);
        this.agentRuntime = agentRuntime;
        this.definitionsLoader = definitionsLoader;
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        String mode = arguments.mode() != null ? arguments.mode() : "local_agent";
        if (!List.of("local_agent", "remote_agent", "in_process_teammate").contains(mode)) {
            return ToolResult.error("Invalid mode. Use local_agent, remote_agent, or in_process_teammate.");
        }

        // Look up agent definition if subagent_type is specified
        AgentDefinition agentDef = null;
        if (arguments.subagentType() != null) {
            agentDef = definitionsLoader.getDefinition(arguments.subagentType());
        }

        // Resolve team and agent name
        String team = arguments.team() != null ? arguments.team() : "default";
        String agentName = arguments.subagentType() != null ? arguments.subagentType() : "agent";
        String prompt = arguments.prompt();

        // Resolve backend: respect OPENHARNESS_TEAMMATE_MODE env var, default to auto-detection
        BackendRegistry registry = BackendRegistry.getInstance();
        String backendType = registry.getPreferredBackend(null);
        TeammateBackend executor = registry.getExecutor(backendType);

        // Wire agentRuntime into InProcessBackend so agents can actually run queries
        if (executor instanceof InProcessBackend inProc && inProc.getAgentRuntime() == null) {
            inProc.setAgentRuntime(agentRuntime);
        }

        String model = arguments.model();
        if (model == null && agentDef != null && agentDef.model() != null) {
            model = agentDef.model();
        }

        List<String> permissions = agentDef != null ? agentDef.permissions() : List.of();

        TeammateSpec config = TeammateSpec.builder()
                .name(agentName)
                .team(team)
                .prompt(prompt)
                .cwd(context.cwd() != null ? context.cwd().toString() : System.getProperty("user.dir"))
                .parentSessionId("main")
                .model(model)
                .command(arguments.command())
                .systemPrompt(agentDef != null ? agentDef.systemPrompt() : null)
                .permissions(permissions)
                .taskType(mode)
                .build();

        try {
            SpawnResult result = executor.spawn(config);

            if (!result.success()) {
                return ToolResult.error(result.error() != null ? result.error() : "Failed to spawn agent");
            }

            // Register with TeamRegistry if a team is specified
            if (arguments.team() != null) {
                com.openharness.common.TeamRegistry teamRegistry =
                        com.openharness.common.TeamRegistry.getInstance();
                try {
                    teamRegistry.addAgent(arguments.team(), result.taskId());
                } catch (IllegalArgumentException e) {
                    teamRegistry.createTeam(arguments.team());
                    teamRegistry.addAgent(arguments.team(), result.taskId());
                }
            }

            String output = String.format(
                    "Spawned agent %s (task_id=%s, backend=%s, description=%s)",
                    result.agentId(), result.taskId(), result.backendType(),
                    arguments.description() != null ? arguments.description() : "");

            return ToolResult.success(output);
        } catch (Exception e) {
            logger.error("Failed to spawn agent: {}", e.getMessage(), e);
            return ToolResult.error(e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
    }

    public record Input(
            String description,
            String prompt,
            String subagentType,
            String model,
            String command,
            String team,
            String mode) {

        public Input {
            if (prompt == null) throw new IllegalArgumentException("prompt is required");
        }
    }
}
