package com.openharness.extensions.memory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scan-time memory header with file-level metadata.
 * Java equivalent of Python memory/types.py MemoryHeader dataclass (22 fields).
 */
public record MemoryScanHeader(
        Path path,
        MemoryEntry.MemoryHeader header,
        float modifiedAt,
        String bodyPreview,
        String relativePath,
        Map<String, Object> frontmatter
) {
    public MemoryScanHeader {
        frontmatter = frontmatter != null ? Map.copyOf(frontmatter) : Map.of();
    }

    // Delegates for convenience — matching Python MemoryHeader field names
    public String title() { return header.name(); }
    public String description() { return header.description(); }
    public String memoryType() { return header.type().name().toLowerCase(); }
    public String id() { return header.id(); }
    public int schemaVersion() { return header.schemaVersion(); }
    public String category() { return header.category(); }
    public int importance() { return header.importance(); }
    public String source() { return header.source(); }
    public String signature() { return header.signature(); }
    public String createdAt() { return formatTime(header.createdAt()); }
    public String updatedAt() { return formatTime(header.updatedAt()); }
    public Integer ttlDays() { return header.ttlDays(); }
    public boolean disabled() { return header.disabled(); }
    public java.util.List<String> supersedes() { return header.supersedes(); }
    public java.util.List<String> tags() { return header.tags(); }
    public String scope() { return header.scope(); }

    private static String formatTime(java.time.Instant instant) {
        if (instant == null) return "";
        return instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }
}
