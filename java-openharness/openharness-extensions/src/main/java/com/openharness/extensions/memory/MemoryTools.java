package com.openharness.extensions.memory;

import com.openharness.common.ToolResult;
import com.openharness.config.MemorySettings;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.util.List;

/**
 * Memory management tools for reading/writing/deleting/searching memories.
 * Java equivalent of Python's memory tools.
 */
public final class MemoryTools {

    public static class MemoryCreateTool extends BaseTool<MemoryCreateTool.Input> {
        public MemoryCreateTool() {
            super("memory_create", "Create a new persistent memory entry.", Input.class);
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            MemoryManager mgr = getManager(ctx);
            MemoryType type = parseType(args.type());
            MemoryEntry entry = mgr.create(type, args.name(), args.description(), args.content());
            return ToolResult.success("Memory created: " + entry.header().id().substring(0, 8)
                    + " (" + args.name() + ")");
        }

        @Override public boolean isReadOnly(Input args) { return false; }

        public record Input(String name, String description, String type, String content) {
            public Input {
                if (name == null) throw new IllegalArgumentException("name is required");
                if (content == null) content = "";
                if (type == null) type = "USER";
            }
        }

        private MemoryType parseType(String t) {
            try { return MemoryType.valueOf(t.toUpperCase()); }
            catch (IllegalArgumentException e) { return MemoryType.USER; }
        }
    }

    public static class MemoryReadTool extends BaseTool<MemoryReadTool.Input> {
        public MemoryReadTool() { super("memory_read", "Read a memory entry by ID.", Input.class); }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            MemoryManager mgr = getManager(ctx);
            MemoryEntry entry = mgr.get(args.memoryId());
            if (entry == null) return ToolResult.error("Memory not found: " + args.memoryId());
            return ToolResult.success("[" + entry.header().type() + "] " + entry.header().name()
                    + "\n" + entry.body());
        }

        @Override public boolean isReadOnly(Input args) { return true; }

        public record Input(String memoryId) {
            public Input { if (memoryId == null) throw new IllegalArgumentException("memoryId is required"); }
        }
    }

    public static class MemorySearchTool extends BaseTool<MemorySearchTool.Input> {
        public MemorySearchTool() {
            super("memory_search", "Search memories by relevance.", Input.class);
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            MemoryManager mgr = getManager(ctx);
            int topK = args.limit() > 0 ? args.limit() : 5;
            List<MemoryEntry.ScoredMemory> results = mgr.search(args.query(), topK);

            if (results.isEmpty()) return ToolResult.success("No matching memories found.");

            StringBuilder sb = new StringBuilder("Memory search results:\n\n");
            for (var scored : results) {
                MemoryEntry.MemoryHeader h = scored.memory().header();
                sb.append("- [").append(String.format("%.2f", scored.score())).append("] ")
                        .append("[").append(h.type()).append("] ")
                        .append(h.name()).append(" — ").append(h.description()).append("\n");
            }
            return ToolResult.success(sb.toString());
        }

        @Override public boolean isReadOnly(Input args) { return true; }

        public record Input(String query, int limit) {
            public Input { if (query == null) throw new IllegalArgumentException("query is required"); }
        }
    }

    public static class MemoryDeleteTool extends BaseTool<MemoryDeleteTool.Input> {
        public MemoryDeleteTool() {
            super("memory_delete", "Delete a memory entry by ID.", Input.class);
        }

        @Override
        public ToolResult execute(Input args, ToolExecutionContext ctx) {
            MemoryManager mgr = getManager(ctx);
            boolean deleted = mgr.delete(args.memoryId());
            return deleted
                    ? ToolResult.success("Memory deleted: " + args.memoryId())
                    : ToolResult.error("Memory not found: " + args.memoryId());
        }

        @Override public boolean isReadOnly(Input args) { return false; }

        public record Input(String memoryId) {
            public Input { if (memoryId == null) throw new IllegalArgumentException("memoryId is required"); }
        }
    }

    private static MemoryManager getManager(ToolExecutionContext ctx) {
        Object mgr = ctx.metadata().get("memoryManager");
        if (mgr instanceof MemoryManager mm) return mm;
        return MemoryManager.createUserManager(new MemorySettings());
    }
}
