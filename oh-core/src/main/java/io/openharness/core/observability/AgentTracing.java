package io.openharness.core.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Micrometer Observation 替代手动 TraceContext。
 * Reactor Context 自动传播 traceId，跨线程自动恢复。
 * Brave 自动拦截 JDBC (MyBatis) + HttpClient 调用。
 */
public class AgentTracing {

    private final ObservationRegistry registry;

    public AgentTracing(ObservationRegistry registry) {
        this.registry = registry;
    }

    public Observation startRequest(String sessionId) {
        return Observation.createNotStarted("oh.request", registry)
            .lowCardinalityKeyValue("sessionId", sessionId)
            .start();
    }

    public Observation startTurn(int turnNumber) {
        return Observation.createNotStarted("oh.agent.turn", registry)
            .lowCardinalityKeyValue("turn", String.valueOf(turnNumber))
            .start();
    }

    public Observation startToolCall(String toolName, String toolUseId) {
        return Observation.createNotStarted("oh.tool.execute", registry)
            .lowCardinalityKeyValue("toolName", toolName)
            .highCardinalityKeyValue("toolUseId", toolUseId)
            .start();
    }
}
