package com.openharness.extensions.swarm;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tmux-based agent execution backend for interactive sessions.
 * Java equivalent of Python tmux pane backend.
 */
public class TmuxBackend implements TeammateBackend {

    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, TeammateStatus> statuses = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "tmux";
    }

    @Override
    public boolean isAvailable() {
        return SpawnUtils.isTmuxAvailable();
    }

    @Override
    public SpawnResult spawn(TeammateSpec spec) {
        String agentId = spec.sessionId();
        try {
            String sessionName = "oh_" + agentId.substring(0, 8);
            Map<String, String> env = SpawnUtils.buildEnv(spec);

            String[] cmd = {
                    "tmux", "new-session", "-d", "-s", sessionName,
                    "-c", spec.worktreePath() != null
                            ? spec.worktreePath().toString()
                            : System.getProperty("user.dir"),
                    "openharness", "agent",
                    "--id", agentId,
                    "--agent-type", spec.name()
            };

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(env);
            Process p = pb.start();
            p.waitFor();

            if (p.exitValue() == 0) {
                sessions.put(agentId, sessionName);
                statuses.put(agentId, TeammateStatus.running(agentId, -1));
            } else {
                throw new IOException("tmux new-session failed with exit code: " + p.exitValue());
            }

            return SpawnResult.success("tmux-" + agentId, agentId, type());
        } catch (Exception e) {
            statuses.put(agentId, new TeammateStatus(
                    agentId, TeammateStatus.State.FAILED,
                    null, java.time.Instant.now(), -1, -1));
            return SpawnResult.failure("tmux-" + agentId, agentId, type(), e.getMessage());
        }
    }

    @Override
    public void sendMessage(String agentId, TeammateMessage message) {
        String session = sessions.get(agentId);
        if (session == null) return;
        try {
            new ProcessBuilder("tmux", "send-keys", "-t", session, message.text(), "Enter")
                    .start().waitFor();
        } catch (Exception e) {
            throw new RuntimeException("Failed to send message to tmux session: " + session, e);
        }
    }

    @Override
    public boolean shutdown(String agentId, boolean force) {
        String session = sessions.remove(agentId);
        if (session != null) {
            try {
                String[] cmd = force
                        ? new String[]{"tmux", "kill-session", "-t", session}
                        : new String[]{"tmux", "kill-session", "-t", session};
                new ProcessBuilder(cmd).start().waitFor();
            } catch (Exception e) {
                // session may already be dead
            }
        }
        statuses.remove(agentId);
        return true;
    }

    @Override
    public TeammateStatus getStatus(String agentId) {
        String session = sessions.get(agentId);
        if (session == null) return TeammateStatus.unknown(agentId);

        try {
            Process p = new ProcessBuilder("tmux", "has-session", "-t", session).start();
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                return statuses.getOrDefault(agentId, TeammateStatus.running(agentId, -1));
            }
            return new TeammateStatus(agentId, TeammateStatus.State.STOPPED,
                    null, java.time.Instant.now(), -1, -1);
        } catch (Exception e) {
            return TeammateStatus.unknown(agentId);
        }
    }
}