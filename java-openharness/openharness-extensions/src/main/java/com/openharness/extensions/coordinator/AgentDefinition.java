package com.openharness.extensions.coordinator;

import com.openharness.permissions.PermissionMode;

import java.nio.file.Path;
import java.util.List;

/**
 * Each agent definition is stored in a .md file's YAML frontmatter.
 * Java equivalent of Python coordinator/agent_definitions.py AgentDefinition.
 */
public record AgentDefinition(
        String name,
        String description,
        String systemPrompt,
        List<String> tools,
        List<String> disallowedTools,
        String model,
        String effort,
        PermissionMode permissionMode,
        Integer maxTurns,
        List<String> skills,
        List<String> mcpServers,
        List<String> requiredMcpServers,
        String color,
        boolean background,
        String initialPrompt,
        String memory,
        String isolation,
        boolean omitClaudeMd,
        String criticalSystemReminder,
        String subagentType,
        String source,
        String filename,
        Path baseDir
) {
    public AgentDefinition {
        if (name == null) throw new IllegalArgumentException("name is required");
        tools = tools != null ? List.copyOf(tools) : List.of();
        disallowedTools = disallowedTools != null ? List.copyOf(disallowedTools) : List.of();
        skills = skills != null ? List.copyOf(skills) : List.of();
        mcpServers = mcpServers != null ? List.copyOf(mcpServers) : List.of();
        requiredMcpServers = requiredMcpServers != null ? List.copyOf(requiredMcpServers) : List.of();
        source = source != null ? source : "builtin";
    }
}
