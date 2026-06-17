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
            super("team_create", "Create a lightweight in-memory team for agent tasks.", Input.class);
            this.teamRegistry = teamRegistry;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            try {
                var team = teamRegistry.createTeam(arguments.name(), arguments.description());
                return ToolResult.success("Created team " + team.name());
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
        }

        public record Input(String name, String description) {
            public Input {
                if (name == null || name.isBlank()) name = "untitled";
                if (description == null) description = "";
            }
        }
    }

    public static class TeamDeleteTool extends BaseTool<TeamDeleteTool.Input> {
        private final TeamRegistry teamRegistry;

        public TeamDeleteTool(TeamRegistry teamRegistry) {
            super("team_delete", "Delete an in-memory team.", Input.class);
            this.teamRegistry = teamRegistry;
        }

        @Override
        public ToolResult execute(Input arguments, ToolExecutionContext context) {
            try {
                teamRegistry.deleteTeam(arguments.name());
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
            return ToolResult.success("Deleted team " + arguments.name());
        }

        public record Input(String name) {
            public Input {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("name is required");
                }
            }
        }
    }
}
