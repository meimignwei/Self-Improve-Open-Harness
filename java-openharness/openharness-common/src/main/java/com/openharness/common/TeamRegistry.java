package com.openharness.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory team registry for multi-agent coordination.
 * Shared between coordinator mode and team tools.
 */
public class TeamRegistry {
    private final ConcurrentHashMap<String, TeamRecord> teams = new ConcurrentHashMap<>();

    public TeamRecord createTeam(String name) {
        String id = UUID.randomUUID().toString();
        TeamRecord record = new TeamRecord(id, name, new ConcurrentHashMap<>(), Instant.now());
        teams.put(id, record);
        return record;
    }

    public void deleteTeam(String teamId) {
        teams.remove(teamId);
    }

    public void addAgent(String teamId, String agentDef, String agentId) {
        TeamRecord record = teams.get(teamId);
        if (record != null) {
            record.members().put(agentId, agentDef);
        }
    }

    public void removeAgent(String teamId, String agentId) {
        TeamRecord record = teams.get(teamId);
        if (record != null) {
            record.members().remove(agentId);
        }
    }

    public List<TeamRecord> listTeams() {
        return List.copyOf(teams.values());
    }

    public TeamRecord get(String teamId) {
        return teams.get(teamId);
    }

    public record TeamRecord(
            String id,
            String name,
            Map<String, String> members,
            Instant createdAt
    ) {}
}
