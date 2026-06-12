package com.openharness.tools;

import com.openharness.common.TeamRegistry;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

/**
 * Team management tools for coordinator mode.
 */
public final class TeamTools {

    private TeamTools() {}

    public static class TeamCreateTool extends BaseTool<TeamCreateTool.Input> {
        private final TeamRegistry teamRegistry;

        public TeamCreateTool(TeamRegistry teamRegistry) {
            super("team_create", "Creates a new team for multi-agent coordination.", Input.class);
            this.teamRegistry = teamRegistry;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            var team = teamRegistry.createTeam(arguments.name());
            return ToolResult.success("Created team '" + team.name() + "' (id: " + team.id() + ").");
        }

        public record Input(String name) {
            public Input {
                if (name == null || name.isBlank()) name = "untitled";
            }
        }
    }

    public static class TeamDeleteTool extends BaseTool<TeamDeleteTool.Input> {
        private final TeamRegistry teamRegistry;

        public TeamDeleteTool(TeamRegistry teamRegistry) {
            super("team_delete", "Deletes a team by ID.", Input.class);
            this.teamRegistry = teamRegistry;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            teamRegistry.deleteTeam(arguments.teamId());
            return ToolResult.success("Deleted team " + arguments.teamId() + ".");
        }

        public record Input(String teamId) {
            public Input {
                if (teamId == null || teamId.isBlank()) {
                    throw new IllegalArgumentException("teamId is required");
                }
            }
        }
    }
}
