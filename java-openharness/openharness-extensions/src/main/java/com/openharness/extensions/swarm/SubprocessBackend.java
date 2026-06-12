package com.openharness.extensions.swarm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subprocess-based agent execution backend.
 * Java equivalent of Python swarm/subprocess_backend.py.
 */
public class SubprocessBackend implements TeammateBackend {

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, TeammateStatus> statuses = new ConcurrentHashMap<>();
    private final Path openharnessBinary;

    public SubprocessBackend(Path openharnessBinary) {
        this.openharnessBinary = openharnessBinary;
    }

    public SubprocessBackend() {
        this(Path.of("openharness"));
    }

    @Override
    public String spawn(TeammateSpec spec) {
        try {
            Map<String, String> env = SpawnUtils.buildEnv(spec);
            ProcessBuilder pb = new ProcessBuilder(
                    openharnessBinary.toString(), "agent",
                    "--id", spec.id(),
                    "--agent-type", spec.agentType(),
                    "--model", spec.model() != null ? spec.model() : "claude-sonnet-4-6"
            );
            if (spec.worktreePath() != null) {
                pb.directory(spec.worktreePath().toFile());
            }
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            processes.put(spec.id(), process);
            statuses.put(spec.id(), TeammateStatus.running(spec.id(), (int) process.pid()));

            Thread.startVirtualThread(() -> {
                try {
                    int exitCode = process.waitFor();
                    TeammateStatus prev = statuses.get(spec.id());
                    statuses.put(spec.id(), new TeammateStatus(
                            spec.id(),
                            exitCode == 0 ? TeammateStatus.State.COMPLETED : TeammateStatus.State.FAILED,
                            prev != null ? prev.startedAt() : null,
                            java.time.Instant.now(),
                            (int) process.pid(),
                            exitCode));
                } catch (InterruptedException e) {
                    statuses.put(spec.id(), new TeammateStatus(
                            spec.id(), TeammateStatus.State.FAILED,
                            null, java.time.Instant.now(), -1, -1));
                }
            });

            return spec.id();
        } catch (IOException e) {
            statuses.put(spec.id(), new TeammateStatus(
                    spec.id(), TeammateStatus.State.FAILED,
                    null, java.time.Instant.now(), -1, -1));
            throw new RuntimeException("Failed to spawn teammate: " + spec.id(), e);
        }
    }

    @Override
    public void sendMessage(String teammateId, String message) {
        Process process = processes.get(teammateId);
        if (process != null && process.isAlive()) {
            try {
                var stdin = process.outputWriter();
                stdin.write(message);
                stdin.write("\n");
                stdin.flush();
            } catch (IOException e) {
                throw new RuntimeException("Failed to send message to: " + teammateId, e);
            }
        }
    }

    @Override
    public TeammateStatus getStatus(String teammateId) {
        return statuses.getOrDefault(teammateId, TeammateStatus.unknown(teammateId));
    }

    @Override
    public void stop(String teammateId) {
        Process process = processes.remove(teammateId);
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        statuses.remove(teammateId);
    }
}
