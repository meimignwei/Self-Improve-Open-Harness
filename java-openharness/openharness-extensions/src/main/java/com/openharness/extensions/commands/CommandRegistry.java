package com.openharness.extensions.commands;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for slash commands (/help, /clear, /model, etc.).
 * Java equivalent of Python's CommandRegistry.
 */
public class CommandRegistry {

    private final Map<String, CommandDefinition> commands = new ConcurrentHashMap<>();

    public CommandRegistry() {
        registerBuiltins();
    }

    private void registerBuiltins() {
        register(new CommandDefinition("help", "Show help information", "core"));
        register(new CommandDefinition("clear", "Clear the conversation history", "core"));
        register(new CommandDefinition("compact", "Compact the conversation context", "core"));
        register(new CommandDefinition("model", "Show or change the current model", "core"));
        register(new CommandDefinition("config", "Show or modify configuration", "core"));
        register(new CommandDefinition("doctor", "Run diagnostics and health checks", "core"));
        register(new CommandDefinition("exit", "Exit the current session", "core"));
        register(new CommandDefinition("status", "Show session status", "core"));
        register(new CommandDefinition("todos", "Show the current task list", "task"));
        register(new CommandDefinition("task", "Create a background task", "task"));
        register(new CommandDefinition("skills", "List available skills", "skill"));
        register(new CommandDefinition("hooks", "Manage hooks", "hook"));
        register(new CommandDefinition("plugins", "Manage plugins", "plugin"));
        register(new CommandDefinition("permissions", "Manage permission modes", "core"));
        register(new CommandDefinition("memory", "Manage memory and knowledge", "memory"));
        register(new CommandDefinition("agents", "Manage sub-agents", "agent"));
        register(new CommandDefinition("mcp", "Manage MCP servers", "mcp"));
        register(new CommandDefinition("sandbox", "Manage sandbox settings", "core"));
        register(new CommandDefinition("feedback", "Send feedback", "core"));
        register(new CommandDefinition("log", "View or configure logging", "core"));
        register(new CommandDefinition("theme", "Change the UI theme", "ui"));
    }

    public void register(CommandDefinition cmd) {
        commands.put(cmd.name(), cmd);
    }

    public Optional<CommandDefinition> get(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    public List<CommandDefinition> listAll() {
        return List.copyOf(commands.values());
    }

    public record CommandDefinition(String name, String description, String category) {}
}
