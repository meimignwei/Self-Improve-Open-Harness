package com.openharness.extensions.swarm;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility for building spawn environments and propagating trace context.
 * Java equivalent of Python swarm/spawn_utils.py.
 */
public final class SpawnUtils {

    private SpawnUtils() {}

    /**
     * Builds the environment variables for a spawned agent process.
     */
    public static Map<String, String> buildEnv(TeammateSpec spec) {
        Map<String, String> env = new HashMap<>(System.getenv());

        if (spec.id() != null) {
            env.put("OPENHARNESS_TEAMMATE_ID", spec.id());
        }
        if (spec.leaderMailboxPath() != null) {
            env.put("OPENHARNESS_LEADER_MAILBOX",
                    spec.leaderMailboxPath().toAbsolutePath().toString());
        }
        if (spec.agentType() != null) {
            env.put("OPENHARNESS_AGENT_TYPE", spec.agentType());
        }
        if (spec.model() != null) {
            env.put("OPENHARNESS_MODEL", spec.model());
        }

        return env;
    }

    /**
     * Injects trace context headers from the current span into the environment.
     */
    public static void injectTraceContext(Map<String, String> env) {
        String traceParent = System.getProperty("opentelemetry.traceparent");
        if (traceParent != null) {
            env.put("TRACEPARENT", traceParent);
        }
        String traceState = System.getProperty("opentelemetry.tracestate");
        if (traceState != null) {
            env.put("TRACESTATE", traceState);
        }
    }
}
