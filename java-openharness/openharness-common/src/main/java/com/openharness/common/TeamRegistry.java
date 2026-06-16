package com.openharness.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory team registry for multi-agent coordination.
 * Java equivalent of Python coordinator/coordinator_mode.py TeamRegistry.
 */
public class TeamRegistry {

    private static volatile TeamRegistry instance;

    private final ConcurrentHashMap<String, TeamRecord> teams = new ConcurrentHashMap<>();

    public static synchronized TeamRegistry getInstance() {
        if (instance == null) {
            instance = new TeamRegistry();
        }
        return instance;
    }

    public TeamRecord createTeam(String name, String description) {
        if (teams.containsKey(name)) {
            throw new IllegalArgumentException("Team '" + name + "' already exists");
        }
        String id = UUID.randomUUID().toString();
        TeamRecord record = new TeamRecord(id, name, description, new ConcurrentHashMap<>(),
                new CopyOnWriteArrayList<>(), Instant.now());
        teams.put(name, record);
        teams.put(id, record);
        return record;
    }

    public TeamRecord createTeam(String name) {
        return createTeam(name, "");
    }

    public void deleteTeam(String identifier) {
        TeamRecord record = teams.remove(identifier);
        if (record == null) {
            throw new IllegalArgumentException("Team '" + identifier + "' does not exist");
        }
        // Remove both name and id entries
        teams.remove(record.name());
    }

    public void addAgent(String teamName, String taskId) {
        TeamRecord record = teams.get(teamName);
        if (record == null) {
            throw new IllegalArgumentException("Team '" + teamName + "' does not exist");
        }
        record.members().put(taskId, taskId);
    }

    public void addAgent(String teamName, String agentDef, String agentId) {
        TeamRecord record = teams.get(teamName);
        if (record != null) {
            record.members().put(agentId, agentDef);
        }
    }

    public void removeAgent(String teamName, String agentId) {
        TeamRecord record = teams.get(teamName);
        if (record != null) {
            record.members().remove(agentId);
        }
    }

    public void sendMessage(String teamName, String message) {
        TeamRecord record = teams.get(teamName);
        if (record != null) {
            record.messages().add(message);
        }
    }

    public List<TeamRecord> listTeams() {
        return teams.values().stream()
                .distinct()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    public TeamRecord get(String identifier) {
        return teams.get(identifier);
    }

    public record TeamRecord(
            String id,
            String name,
            String description,
            Map<String, String> members,
            List<String> messages,
            Instant createdAt) {

        public TeamRecord {
            if (description == null) description = "";
        }
    }
}
