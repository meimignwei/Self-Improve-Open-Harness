package com.openharness.extensions.swarm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages team lifecycle: creation, health monitoring, and teardown.
 * Java equivalent of Python swarm/team_lifecycle.py.
 */
public class TeamLifecycle {

    private final Map<String, TeamRecord> teams = new ConcurrentHashMap<>();
    private final BackendRegistry registry;

    public TeamLifecycle(BackendRegistry registry) {
        this.registry = registry;
    }

    public record TeamRecord(
            String teamId,
            Map<String, String> memberIds,
            Path mailboxDir,
            Path syncDir,
            long createdAt
    ) {}

    public TeamRecord createTeam(String teamId, Map<String, String> members,
                                  Path mailboxDir, Path syncDir) {
        try {
            Files.createDirectories(mailboxDir);
            Files.createDirectories(syncDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create team dirs", e);
        }

        TeamRecord record = new TeamRecord(teamId, members, mailboxDir, syncDir,
                System.currentTimeMillis());
        teams.put(teamId, record);
        return record;
    }

    public boolean isAlive(String teamId) {
        TeamRecord record = teams.get(teamId);
        if (record == null) return false;

        for (String memberId : record.memberIds().values()) {
            TeammateBackend backend = registry.getDefault();
            if (backend != null && !backend.isAlive(memberId)) {
                return false;
            }
        }
        return true;
    }

    public void destroy(String teamId) {
        TeamRecord record = teams.remove(teamId);
        if (record == null) return;

        TeammateBackend backend = registry.getDefault();
        if (backend != null) {
            for (String memberId : record.memberIds().values()) {
                backend.stop(memberId);
            }
        }

        try {
            deleteDirectory(record.mailboxDir());
            deleteDirectory(record.syncDir());
        } catch (IOException ignored) {}
    }

    public TeamRecord get(String teamId) {
        return teams.get(teamId);
    }

    public Map<String, TeamRecord> all() {
        return Map.copyOf(teams);
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(f -> {
                            try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                        });
            }
        }
    }
}
