package com.openharness.extensions.swarm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton registry for TeammateBackend implementations.
 * Java equivalent of Python swarm/registry.py.
 */
public final class BackendRegistry {

    private static final BackendRegistry INSTANCE = new BackendRegistry();

    private final Map<String, TeammateBackend> backends = new ConcurrentHashMap<>();
    private volatile String defaultBackend = "inprocess";

    private BackendRegistry() {}

    public static BackendRegistry getInstance() {
        return INSTANCE;
    }

    public void register(String name, TeammateBackend backend) {
        backends.put(name, backend);
    }

    public TeammateBackend get(String name) {
        return backends.get(name);
    }

    public TeammateBackend getDefault() {
        return backends.get(defaultBackend);
    }

    public void setDefault(String name) {
        if (backends.containsKey(name)) {
            this.defaultBackend = name;
        }
    }

    public Map<String, TeammateBackend> all() {
        return Map.copyOf(backends);
    }
}
