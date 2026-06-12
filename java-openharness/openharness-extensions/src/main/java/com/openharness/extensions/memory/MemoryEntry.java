package com.openharness.extensions.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Markdown 文件 + YAML frontmatter 格式。
 * Java equivalent of Python MemoryEntry.
 */
public record MemoryEntry(
        MemoryHeader header,
        String body
) {

    public record MemoryHeader(
            int schemaVersion,
            String id,
            String name,
            String description,
            MemoryType type,
            String category,
            int importance,
            String source,
            String signature,
            Instant createdAt,
            Instant updatedAt,
            Integer ttlDays,
            boolean disabled,
            List<String> supersedes
    ) {
        public MemoryHeader {
            if (schemaVersion == 0) schemaVersion = 2;
            if (id == null) id = UUID.randomUUID().toString();
            if (importance < 1) importance = 5;
            if (importance > 10) importance = 10;
            supersedes = supersedes != null ? List.copyOf(supersedes) : List.of();
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int schemaVersion = 2;
            private String id = UUID.randomUUID().toString();
            private String name;
            private String description;
            private MemoryType type;
            private String category;
            private int importance = 5;
            private String source;
            private String signature;
            private Instant createdAt = Instant.now();
            private Instant updatedAt = Instant.now();
            private Integer ttlDays;
            private boolean disabled;
            private List<String> supersedes = List.of();

            public Builder schemaVersion(int v) { schemaVersion = v; return this; }
            public Builder id(String v) { id = v; return this; }
            public Builder name(String v) { name = v; return this; }
            public Builder description(String v) { description = v; return this; }
            public Builder type(MemoryType v) { type = v; return this; }
            public Builder category(String v) { category = v; return this; }
            public Builder importance(int v) { importance = v; return this; }
            public Builder source(String v) { source = v; return this; }
            public Builder signature(String v) { signature = v; return this; }
            public Builder createdAt(Instant v) { createdAt = v; return this; }
            public Builder updatedAt(Instant v) { updatedAt = v; return this; }
            public Builder ttlDays(Integer v) { ttlDays = v; return this; }
            public Builder disabled(boolean v) { disabled = v; return this; }
            public Builder supersedes(List<String> v) { supersedes = v; return this; }

            public MemoryHeader build() {
                return new MemoryHeader(schemaVersion, id, name, description, type,
                        category, importance, source, signature, createdAt, updatedAt,
                        ttlDays, disabled, supersedes);
            }
        }
    }

    public record ScoredMemory(MemoryEntry memory, double score) {}

    public static MemoryEntry create(MemoryType type, String name, String description, String body) {
        String signature = MemorySignature.compute(name, body);
        Instant now = Instant.now();
        MemoryHeader header = new MemoryHeader(2, UUID.randomUUID().toString(),
                name, description, type, null, 5, null, signature, now, now,
                null, false, List.of());
        return new MemoryEntry(header, body);
    }

    public MemoryEntry withUpdatedBody(String newBody) {
        MemoryHeader newHeader = new MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.category(), header.importance(), header.source(),
                MemorySignature.compute(header.name(), newBody),
                header.createdAt(), Instant.now(),
                header.ttlDays(), header.disabled(), header.supersedes());
        return new MemoryEntry(newHeader, newBody);
    }

    public MemoryEntry withImportance(int importance) {
        MemoryHeader newHeader = new MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.category(), importance, header.source(),
                header.signature(), header.createdAt(), Instant.now(),
                header.ttlDays(), header.disabled(), header.supersedes());
        return new MemoryEntry(newHeader, body);
    }

    public MemoryEntry withDisabled(boolean disabled) {
        MemoryHeader newHeader = new MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.category(), header.importance(), header.source(),
                header.signature(), header.createdAt(), Instant.now(),
                header.ttlDays(), disabled, header.supersedes());
        return new MemoryEntry(newHeader, body);
    }

    public boolean isExpired() {
        if (header.ttlDays() == null) return false;
        return Instant.now().isAfter(header.createdAt().plusSeconds(header.ttlDays() * 86400L));
    }
}
