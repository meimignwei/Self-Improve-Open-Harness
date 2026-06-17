package com.openharness.extensions.memory;

import com.openharness.config.Paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Agent-scoped memory paths and snapshot initialization.
 * Java equivalent of Python memory/agent.py.
 */
public final class MemoryAgentService {

    private static final String MEMORY_INDEX = "MEMORY.md";
    private static final String SNAPSHOT_DIR_NAME = "agent-memory-snapshots";

    private MemoryAgentService() {}

    /**
     * Python sanitize_agent_type: return a path-safe agent type.
     */
    public static String sanitizeAgentType(String agentType) {
        if (agentType == null || agentType.isBlank()) return "default";
        String safe = agentType.strip().replaceAll("[^a-zA-Z0-9_.-]+", "_");
        return safe.replaceAll("^[._]+|[._]+$", "").isEmpty() ? "default" : safe.replaceAll("^[._]+|[._]+$", "");
    }

    /**
     * Python get_agent_memory_dir: user, project, or local scope.
     */
    public static Path getAgentMemoryDir(Path cwd, String agentType, String scope) {
        String safe = sanitizeAgentType(agentType);
        return switch (scope) {
            case "project" -> Paths.projectMemoryDir(cwd).resolve("agent").resolve(safe);
            case "local" -> cwd.toAbsolutePath().normalize()
                    .resolve(".openharness").resolve("agent-memory-local").resolve(safe);
            default -> Paths.dataDir().resolve("agent-memory").resolve(safe);
        };
    }

    /**
     * Python ensure_agent_memory_vault: create and return an agent-scoped memory vault.
     */
    public static Path ensureAgentMemoryVault(Path cwd, String agentType, String scope) {
        Path memoryDir = getAgentMemoryDir(cwd, agentType, scope);
        try {
            Files.createDirectories(memoryDir);
            Path entrypoint = memoryDir.resolve(MEMORY_INDEX);
            if (!Files.exists(entrypoint)) {
                Files.writeString(entrypoint, "# Memory Index\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create agent memory vault: " + memoryDir, e);
        }
        return memoryDir;
    }

    /**
     * Python get_agent_memory_entrypoint: return agent memory MEMORY.md path.
     */
    public static Path getAgentMemoryEntrypoint(Path cwd, String agentType, String scope) {
        return ensureAgentMemoryVault(cwd, agentType, scope).resolve(MEMORY_INDEX);
    }

    /**
     * Python get_agent_snapshot_dir: return project snapshot directory for agent type.
     */
    public static Path getAgentSnapshotDir(Path cwd, String agentType) {
        return cwd.toAbsolutePath().normalize()
                .resolve(".openharness").resolve(SNAPSHOT_DIR_NAME)
                .resolve(sanitizeAgentType(agentType));
    }

    /**
     * Python initialize_agent_memory_from_snapshot: copy snapshot to agent memory.
     */
    public static Path initializeAgentMemoryFromSnapshot(Path cwd, String agentType,
                                                          String scope, boolean replace) {
        Path snapshotDir = getAgentSnapshotDir(cwd, agentType);
        if (!Files.exists(snapshotDir)) return null;

        Path target = ensureAgentMemoryVault(cwd, agentType, scope);
        try {
            if (replace && Files.exists(target)) {
                try (Stream<Path> files = Files.walk(target)) {
                    files.sorted(java.util.Comparator.reverseOrder())
                            .forEach(f -> {
                                try { Files.deleteIfExists(f); } catch (IOException ignored) { }
                            });
                }
                Files.createDirectories(target);
            }

            try (Stream<Path> files = Files.walk(snapshotDir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".md"))
                        .forEach(src -> {
                            try {
                                Path rel = snapshotDir.relativize(src);
                                Path dest = target.resolve(rel);
                                Files.createDirectories(dest.getParent());
                                if (replace || !Files.exists(dest) || isDefaultAgentIndex(dest)) {
                                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                                }
                            } catch (IOException ignored) { }
                        });
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize agent memory from snapshot", e);
        }
        return target;
    }

    /**
     * Python _is_default_agent_index: check if file is a default empty index.
     */
    private static boolean isDefaultAgentIndex(Path path) {
        if (!path.getFileName().toString().equals(MEMORY_INDEX) || !Files.exists(path)) {
            return false;
        }
        try {
            return Files.readString(path).startsWith("# Memory Index\n");
        } catch (IOException e) {
            return false;
        }
    }
}
