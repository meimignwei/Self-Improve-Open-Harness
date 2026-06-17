package com.openharness.extensions.swarm;

/**
 * Protocol for teammate execution backends.
 * Abstracts spawn/messaging/shutdown across subprocess, in-process, and tmux backends.
 * Java equivalent of Python swarm/types.py TeammateExecutor protocol.
 */
public interface TeammateBackend {

    /** The type identifier for this backend (e.g. "subprocess", "in_process", "tmux"). */
    String type();

    /** Return true if this backend is available on the system. */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Spawn a new teammate with the given configuration.
     * @return SpawnResult with task_id, agent_id, backend_type, and success status.
     */
    SpawnResult spawn(TeammateSpec spec);

    /**
     * Send a message to a running teammate.
     */
    void sendMessage(String agentId, TeammateMessage message);

    /**
     * Terminate a teammate.
     * @param agentId The agent to terminate.
     * @param force If true, kill immediately. If false, attempt graceful shutdown.
     * @return true if the agent was terminated successfully.
     */
    boolean shutdown(String agentId, boolean force);

    default boolean shutdown(String agentId) {
        return shutdown(agentId, false);
    }

    /** Legacy: alias for shutdown with force=false. */
    default void stop(String agentId) {
        shutdown(agentId, false);
    }

    /** Return the current status of a teammate. */
    TeammateStatus getStatus(String agentId);

    default boolean isAlive(String agentId) {
        TeammateStatus status = getStatus(agentId);
        return status != null && status.state() == TeammateStatus.State.RUNNING;
    }
}
