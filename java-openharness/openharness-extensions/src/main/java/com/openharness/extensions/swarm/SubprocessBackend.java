package com.openharness.extensions.swarm;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subprocess-based agent execution backend.
 * Java equivalent of Python swarm/subprocess_backend.py.
 */
public class SubprocessBackend implements TeammateBackend {

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, TeammateStatus> statuses = new ConcurrentHashMap<>();
    private final String openharnessCommand;

    public SubprocessBackend(String openharnessCommand) {
        this.openharnessCommand = openharnessCommand;
    }

    public SubprocessBackend() {
        this(SpawnUtils.getTeammateCommand());
    }

    @Override
    public String type() {
        return "subprocess";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public SpawnResult spawn(TeammateSpec spec) {
        String agentId = spec.sessionId();
        try {
            Map<String, String> env = SpawnUtils.buildEnv(spec);
            ProcessBuilder pb = new ProcessBuilder(
                    openharnessCommand, "-m", "openharness", "agent",
                    "--id", agentId,
                    "--agent-type", spec.name()
            );
            if (spec.model() != null) {
                pb.command().add("--model");
                pb.command().add(spec.model());
            }
            if (spec.worktreePath() != null) {
                pb.directory(spec.worktreePath().toFile());
            }
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            processes.put(agentId, process);
            statuses.put(agentId, TeammateStatus.running(agentId, (int) process.pid()));

            Thread.startVirtualThread(() -> {
                try {
                    int exitCode = process.waitFor();
                    TeammateStatus prev = statuses.get(agentId);
                    statuses.put(agentId, new TeammateStatus(
                            agentId,
                            exitCode == 0 ? TeammateStatus.State.COMPLETED : TeammateStatus.State.FAILED,
                            prev != null ? prev.startedAt() : null,
                            java.time.Instant.now(),
                            (int) process.pid(),
                            exitCode));
                } catch (InterruptedException e) {
                    statuses.put(agentId, new TeammateStatus(
                            agentId, TeammateStatus.State.FAILED,
                            null, java.time.Instant.now(), -1, -1));
                }
            });

            return SpawnResult.success("subproc-" + agentId, agentId, type());
        } catch (IOException e) {
            statuses.put(agentId, new TeammateStatus(
                    agentId, TeammateStatus.State.FAILED,
                    null, java.time.Instant.now(), -1, -1));
            return SpawnResult.failure("subproc-" + agentId, agentId, type(), e.getMessage());
        }
    }

    @Override
    public void sendMessage(String agentId, TeammateMessage message) {
        Process process = processes.get(agentId);
        if (process != null && process.isAlive()) {
            try {
                var stdin = process.outputWriter();
                stdin.write(message.text());
                stdin.write("\n");
                stdin.flush();
            } catch (IOException e) {
                throw new RuntimeException("Failed to send message to: " + agentId, e);
            }
        }
    }

    @Override
    public boolean shutdown(String agentId, boolean force) {
        Process process = processes.remove(agentId);
        if (process == null || !process.isAlive()) {
            statuses.remove(agentId);
            return true;
        }
        if (force) {
            process.destroyForcibly();
        } else {
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
        statuses.remove(agentId);
        return true;
    }

    @Override
    public TeammateStatus getStatus(String agentId) {
        return statuses.getOrDefault(agentId, TeammateStatus.unknown(agentId));
    }
}