package com.openharness.tools;

import com.openharness.common.TeamRegistry;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TeamToolsTest {

    private TeamRegistry teamRegistry;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        teamRegistry = new TeamRegistry();
        ctx = new ToolExecutionContext(Path.of("."));
    }

    @Test
    void teamCreateShouldReturnTeamId() {
        var tool = new TeamTools.TeamCreateTool(teamRegistry);
        var result = tool.execute(new TeamTools.TeamCreateTool.Input("alpha"), ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("alpha"));
        assertTrue(result.content().contains("id:"));
    }

    @Test
    void teamCreateShouldDefaultName() {
        var tool = new TeamTools.TeamCreateTool(teamRegistry);
        var result = tool.execute(new TeamTools.TeamCreateTool.Input(null), ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("untitled"));
    }

    @Test
    void teamDeleteShouldRemoveTeam() {
        var create = new TeamTools.TeamCreateTool(teamRegistry);
        var created = create.execute(new TeamTools.TeamCreateTool.Input("beta"), ctx);
        assertFalse(created.isError());

        var team = teamRegistry.listTeams().getFirst();

        var delete = new TeamTools.TeamDeleteTool(teamRegistry);
        var result = delete.execute(new TeamTools.TeamDeleteTool.Input(team.id()), ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("Deleted"));
        assertTrue(teamRegistry.listTeams().isEmpty());
    }

    @Test
    void teamDeleteShouldRejectNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TeamTools.TeamDeleteTool.Input(null));
    }
}
