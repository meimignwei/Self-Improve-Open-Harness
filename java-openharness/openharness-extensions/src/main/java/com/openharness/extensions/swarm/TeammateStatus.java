package com.openharness.extensions.swarm;

import java.time.Instant;

/**
 * Runtime status of a teammate agent.
 * Java equivalent of Python swarm/types.py TeammateStatus.
 */
public record TeammateStatus(
        String id,
        State state,
        Instant startedAt,
        Instant lastActiveAt,
        int pid,
        int exitCode
) {
    public enum State { RUNNING, COMPLETED, FAILED, STOPPED, UNKNOWN }

    public static TeammateStatus unknown(String id) {
        return new TeammateStatus(id, State.UNKNOWN, null, null, -1, -1);
    }

    public static TeammateStatus running(String id, int pid) {
        Instant now = Instant.now();
        return new TeammateStatus(id, State.RUNNING, now, now, pid, -1);
    }
}
