package com.openharness.extensions.swarm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tmux-based agent execution backend for interactive sessions.
 * Java equivalent of Python swarm/tmux.py.
 */
public class TmuxBackend implements TeammateBackend {

    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, TeammateStatus> statuses = new ConcurrentHashMap<>();

    @Override
    public String spawn(TeammateSpec spec) {
        try {
            String sessionName = "oh_" + spec.id().substring(0, 8);
            Map<String, String> env = SpawnUtils.buildEnv(spec);

            String[] cmd = {
                    "tmux", "new-session", "-d", "-s", sessionName,
                    "-c", spec.worktreePath() != null
                            ? spec.worktreePath().toString()
                            : System.getProperty("user.dir"),
                    "openharness", "agent",
                    "--id", spec.id(),
                    "--agent-type", spec.agentType()
            };

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(env);
            Process p = pb.start();
            p.waitFor();

            if (p.exitValue() == 0) {
                sessions.put(spec.id(), sessionName);
                statuses.put(spec.id(), TeammateStatus.running(spec.id(), -1));
            } else {
                throw new IOException("tmux new-session failed with exit code: " + p.exitValue());
            }

            return spec.id();
        } catch (Exception e) {
            statuses.put(spec.id(), new TeammateStatus(
                    spec.id(), TeammateStatus.State.FAILED,
                    null, java.time.Instant.now(), -1, -1));
            throw new RuntimeException("Failed to spawn tmux teammate: " + spec.id(), e);
        }
    }

    @Override
    public void sendMessage(String teammateId, String message) {
        String session = sessions.get(teammateId);
        if (session == null) return;
        try {
            new ProcessBuilder("tmux", "send-keys", "-t", session, message, "Enter")
                    .start().waitFor();
        } catch (Exception e) {
            throw new RuntimeException("Failed to send message to tmux session: " + session, e);
        }
    }

    @Override
    public TeammateStatus getStatus(String teammateId) {
        String session = sessions.get(teammateId);
        if (session == null) return TeammateStatus.unknown(teammateId);

        try {
            Process p = new ProcessBuilder("tmux", "has-session", "-t", session).start();
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                return statuses.getOrDefault(teammateId, TeammateStatus.running(teammateId, -1));
            }
            return new TeammateStatus(teammateId, TeammateStatus.State.STOPPED,
                    null, java.time.Instant.now(), -1, -1);
        } catch (Exception e) {
            return TeammateStatus.unknown(teammateId);
        }
    }

    @Override
    public void stop(String teammateId) {
        String session = sessions.remove(teammateId);
        if (session != null) {
            try {
                new ProcessBuilder("tmux", "kill-session", "-t", session).start().waitFor();
            } catch (Exception e) {
                // session may already be dead
            }
        }
        statuses.remove(teammateId);
    }
}
