package io.agentscope.core.middleware;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [STUB] AgentScope MiddlewareContext 本地桩。
 * AgentScope 2.0.0-SNAPSHOT 可用后删除。
 */
public class MiddlewareContext {

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public String getSessionId() {
        return (String) attributes.getOrDefault("sessionId", "unknown");
    }

    public int getTurnNumber() {
        return (int) attributes.getOrDefault("turnNumber", 0);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }
}
