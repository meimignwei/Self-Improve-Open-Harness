package com.openharness.extensions.memory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds memory context prompt for specific agent types.
 * Java equivalent of Python memory/agent.py.
 */
public class AgentMemoryContext {

    private final MemoryManager memoryManager;

    public AgentMemoryContext(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * Builds a prompt section with relevant memories for the given agent type.
     */
    public String buildPrompt(String agentType, String queryContext) {
        List<MemoryEntry.ScoredMemory> results = memoryManager.search(queryContext, 10);

        if (results.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("<memory-context>\n");

        for (MemoryEntry.ScoredMemory scored : results) {
            MemoryEntry.MemoryHeader h = scored.memory().header();
            sb.append("<memory type=\"").append(h.type().name().toLowerCase())
                    .append("\" name=\"").append(escapeXml(h.name()))
                    .append("\" score=\"").append(String.format("%.2f", scored.score())).append("\">\n");
            sb.append(scored.memory().body());
            sb.append("\n</memory>\n");
        }

        sb.append("</memory-context>");
        return sb.toString();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
