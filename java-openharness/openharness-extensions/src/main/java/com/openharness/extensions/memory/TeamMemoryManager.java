package com.openharness.extensions.memory;

import com.openharness.config.MemorySettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Team-shared memory with access token validation.
 * Java equivalent of Python memory/team.py.
 */
public class TeamMemoryManager {

    private final Map<String, MemoryManager> teamManagers = new ConcurrentHashMap<>();
    private final Map<String, String> teamSecrets = new ConcurrentHashMap<>();
    private final Path sharedMemoryRoot;
    private final MemorySettings settings;

    public TeamMemoryManager(Path sharedMemoryRoot, MemorySettings settings) {
        this.sharedMemoryRoot = sharedMemoryRoot;
        this.settings = settings;
    }

    public String createTeam(String teamId, String secret) {
        teamSecrets.put(teamId, secret);
        Path teamDir = sharedMemoryRoot.resolve(teamId);
        teamManagers.put(teamId, new MemoryManager(teamDir, settings));
        return secret;
    }

    public boolean validateWriteAccess(String teamId, String secret) {
        String stored = teamSecrets.get(teamId);
        return stored != null && stored.equals(secret);
    }

    public MemoryManager getTeamMemory(String teamId) {
        return teamManagers.computeIfAbsent(teamId, id -> {
            Path teamDir = sharedMemoryRoot.resolve(id);
            return new MemoryManager(teamDir, settings);
        });
    }

    public MemoryEntry createMemory(String teamId, String secret,
                                     MemoryType type, String name, String description, String body) {
        if (!validateWriteAccess(teamId, secret)) {
            throw new SecurityException("Invalid team secret for: " + teamId);
        }
        return getTeamMemory(teamId).create(type, name, description, body);
    }

    public List<MemoryEntry.ScoredMemory> search(String teamId, String query, int topK) {
        MemoryManager mgr = teamManagers.get(teamId);
        if (mgr == null) return List.of();
        return mgr.search(query, topK);
    }

    public void destroyTeam(String teamId) {
        teamManagers.remove(teamId);
        teamSecrets.remove(teamId);
    }
}
