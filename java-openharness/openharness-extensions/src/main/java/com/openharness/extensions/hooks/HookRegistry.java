package com.openharness.extensions.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry that maps HookEvents to their HookDefinitions.
 * Java equivalent of Python's HookRegistry.
 */
public class HookRegistry {

    private final Map<HookEvent, List<HookDefinition>> hooks = new ConcurrentHashMap<>();

    public void register(HookEvent event, HookDefinition hook) {
        hooks.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(hook);
    }

    public void unregister(HookEvent event, HookDefinition hook) {
        List<HookDefinition> list = hooks.get(event);
        if (list != null) {
            list.remove(hook);
        }
    }

    public void clear() {
        hooks.clear();
    }

    public List<HookDefinition> get(HookEvent event) {
        List<HookDefinition> list = hooks.getOrDefault(event, List.of());
        return list.stream()
                .sorted(java.util.Comparator.comparingInt(HookDefinition::priority))
                .toList();
    }

    public Map<HookEvent, List<HookDefinition>> all() {
        return Map.copyOf(hooks);
    }
}
