package io.openharness.core.commands;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CommandRegistry {

    private final Map<String, OhCommand> commands = new ConcurrentHashMap<>();

    public void register(OhCommand cmd) {
        if (commands.containsKey(cmd.name())) {
            throw new IllegalArgumentException("Duplicate command: " + cmd.name());
        }
        commands.put(cmd.name(), cmd);
    }

    public OhCommand get(String name) {
        return commands.get(name);
    }

    public Collection<OhCommand> listAll() {
        return commands.values();
    }

    public boolean exists(String name) {
        return commands.containsKey(name);
    }
}
