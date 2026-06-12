package com.openharness.extensions.coordinator;

import com.openharness.common.OpenHarnessObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinator mode: system prompt, tool set, and team management.
 * Java equivalent of Python coordinator/coordinator_mode.py.
 */
public class CoordinatorMode {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    public static boolean isEnabled() {
        return System.getenv("CLAUDE_CODE_COORDINATOR_MODE") != null;
    }

    public static List<String> getTools() {
        return List.of("agent", "send_message", "task_stop");
    }

    /**
     * Team registry for coordinator-managed multi-agent teams.
     */
    public static class TeamRegistry {
        private final ConcurrentHashMap<String, TeamRecord> teams = new ConcurrentHashMap<>();

        public TeamRecord createTeam(String name) {
            String id = java.util.UUID.randomUUID().toString();
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
    }

    public record TeamRecord(
            String id,
            String name,
            Map<String, String> members,
            Instant createdAt
    ) {}
}
