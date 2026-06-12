package com.openharness.extensions.swarm;

/**
 * Unified abstraction for agent execution backends.
 * Java equivalent of Python swarm/ TeammateBackend ABC.
 */
public interface TeammateBackend {

    String spawn(TeammateSpec spec);

    void sendMessage(String teammateId, String message);

    TeammateStatus getStatus(String teammateId);

    void stop(String teammateId);

    default boolean isAlive(String teammateId) {
        TeammateStatus status = getStatus(teammateId);
        return status.state() == TeammateStatus.State.RUNNING;
    }
}
