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
                .sorted(java.util.Comparator.comparingInt(HookDefinition::priority).reversed())
                .toList();
    }

    public Map<HookEvent, List<HookDefinition>> all() {
        return Map.copyOf(hooks);
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (HookEvent event : HookEvent.values()) {
            List<HookDefinition> list = get(event);
            if (list.isEmpty()) continue;
            sb.append(event.name().toLowerCase()).append(":\n");
            for (HookDefinition hook : list) {
                String detail = switch (hook) {
                    case HookDefinition.CommandHook ch -> ch.command();
                    case HookDefinition.PromptHook ph -> ph.prompt();
                    case HookDefinition.HttpHook hh -> hh.url();
                    case HookDefinition.AgentHook ah -> ah.prompt();
                };
                String suffix = "";
                if (hook.matcher() != null && !hook.matcher().isBlank()) {
                    suffix += " matcher=" + hook.matcher();
                }
                if (hook.priority() != 0) {
                    suffix += " priority=" + hook.priority();
                }
                sb.append("  - ").append(hook.getClass().getSimpleName())
                        .append(suffix).append(": ").append(detail).append("\n");
            }
        }
        return sb.toString();
    }
}
