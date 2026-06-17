package com.openharness.extensions.memory;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

/**
 * Markdown file + YAML frontmatter memory entry.
 * Java equivalent of Python memory/schema.py with SCHEMA_VERSION=1 and 18 frontmatter fields.
 *
 * Frontmatter fields (stable order matching Python FRONTMATTER_FIELDS):
 * schema_version, id, name, description, type, scope, category, importance,
 * source, signature, created_at, updated_at, ttl_days, disabled, supersedes, tags
 */
public record MemoryEntry(
        MemoryHeader header,
        String body
) {

    public static final int SCHEMA_VERSION = 1;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public record MemoryHeader(
            int schemaVersion,
            String id,
            String name,
            String description,
            MemoryType type,
            String scope,
            String category,
            int importance,
            String source,
            String signature,
            Instant createdAt,
            Instant updatedAt,
            Integer ttlDays,
            boolean disabled,
            List<String> supersedes,
            List<String> tags
    ) {
        public MemoryHeader {
            if (schemaVersion == 0) schemaVersion = SCHEMA_VERSION;
            if (id == null || id.isBlank()) id = generateMemoryId();
            if (scope == null || scope.isBlank()) scope = "project";
            supersedes = supersedes != null ? List.copyOf(supersedes) : List.of();
            tags = tags != null ? List.copyOf(tags) : List.of();
        }

        /**
         * Python: base_time = parse_datetime(updated_at) or parse_datetime(created_at)
         */
        public boolean isExpired() {
            if (ttlDays == null || ttlDays <= 0) return false;
            Instant baseTime = updatedAt != null ? updatedAt : createdAt;
            if (baseTime == null) return false;
            return Instant.now().isAfter(baseTime.plus(ttlDays, ChronoUnit.DAYS));
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int schemaVersion = SCHEMA_VERSION;
            private String id = generateMemoryId();
            private String name;
            private String description;
            private MemoryType type = MemoryType.PROJECT;
            private String scope = "project";
            private String category;
            private int importance = 5;
            private String source;
            private String signature;
            private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            private Instant updatedAt = createdAt;
            private Integer ttlDays;
            private boolean disabled;
            private List<String> supersedes = List.of();
            private List<String> tags = List.of();

            public Builder schemaVersion(int v) { schemaVersion = v; return this; }
            public Builder id(String v) { id = v; return this; }
            public Builder name(String v) { name = v; return this; }
            public Builder description(String v) { description = v; return this; }
            public Builder type(MemoryType v) { type = v; return this; }
            public Builder scope(String v) { scope = v; return this; }
            public Builder category(String v) { category = v; return this; }
            public Builder importance(int v) { importance = v; return this; }
            public Builder source(String v) { source = v; return this; }
            public Builder signature(String v) { signature = v; return this; }
            public Builder createdAt(Instant v) { createdAt = v.truncatedTo(ChronoUnit.SECONDS); return this; }
            public Builder updatedAt(Instant v) { updatedAt = v.truncatedTo(ChronoUnit.SECONDS); return this; }
            public Builder ttlDays(Integer v) { ttlDays = v; return this; }
            public Builder disabled(boolean v) { disabled = v; return this; }
            public Builder supersedes(List<String> v) { supersedes = v; return this; }
            public Builder tags(List<String> v) { tags = v; return this; }

            public MemoryHeader build() {
                return new MemoryHeader(schemaVersion, id, name, description, type,
                        scope, category, importance, source, signature, createdAt, updatedAt,
                        ttlDays, disabled, supersedes, tags);
            }
        }
    }

    public record ScoredMemory(MemoryEntry memory, double score) {}

    // ------------------------------------------------------------------
    // ID generation — Python generate_memory_id()
    // ------------------------------------------------------------------

    /**
     * Python: mem-YYYYMMDD-HHMMSS-<8hex>
     */
    public static String generateMemoryId() {
        return generateMemoryId(Instant.now());
    }

    public static String generateMemoryId(Instant now) {
        Instant utc = now.truncatedTo(ChronoUnit.SECONDS);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(utc);
        byte[] randomBytes = new byte[4];
        SECURE_RANDOM.nextBytes(randomBytes);
        return "mem-" + timestamp + "-" + HexFormat.of().formatHex(randomBytes);
    }

    // ------------------------------------------------------------------
    // Signatures — delegated to MemorySignature
    // ------------------------------------------------------------------

    public static String computeSignature(String content, String memoryType, String category) {
        return MemorySignature.compute(content, memoryType, category);
    }

    // ------------------------------------------------------------------
    // Factory methods
    // ------------------------------------------------------------------

    public static MemoryEntry create(MemoryType type, String name, String description, String body) {
        return create(type, name, description, body, "knowledge");
    }

    public static MemoryEntry create(MemoryType type, String name, String description, String body, String category) {
        String typeStr = type.name().toLowerCase();
        String cat = category != null ? category : "knowledge";
        String signature = MemorySignature.compute(body, typeStr, cat);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MemoryHeader header = new MemoryHeader(
                SCHEMA_VERSION, generateMemoryId(now), name, description,
                type, "project", cat, 5, null, signature,
                now, now, null, false, List.of(), List.of());
        return new MemoryEntry(header, body);
    }

    public MemoryEntry withUpdatedBody(String newBody) {
        String typeStr = header.type().name().toLowerCase();
        String cat = header.category() != null ? header.category() : "knowledge";
        String newSig = MemorySignature.compute(newBody, typeStr, cat);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MemoryHeader newHeader = new MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.scope(), header.category(), header.importance(),
                header.source(), newSig,
                header.createdAt(), now,
                header.ttlDays(), header.disabled(), header.supersedes(), header.tags());
        return new MemoryEntry(newHeader, newBody);
    }

    public MemoryEntry withImportance(int importance) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MemoryHeader newHeader = new MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.scope(), header.category(), importance,
                header.source(), header.signature(),
                header.createdAt(), now,
                header.ttlDays(), header.disabled(), header.supersedes(), header.tags());
        return new MemoryEntry(newHeader, body);
    }

    public MemoryEntry withDisabled(boolean disabled) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MemoryHeader newHeader = new MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.scope(), header.category(), header.importance(),
                header.source(), header.signature(),
                header.createdAt(), now,
                header.ttlDays(), disabled, header.supersedes(), header.tags());
        return new MemoryEntry(newHeader, body);
    }

    // ------------------------------------------------------------------
    // Coerce helpers — matching Python schema.py
    // ------------------------------------------------------------------

    public static int coerceInt(Object value, int defaultValue) {
        try {
            if (value instanceof Number n) return n.intValue();
            if (value instanceof String s) return Integer.parseInt(s);
        } catch (Exception e) { /* fall through */ }
        return defaultValue;
    }

    public static Integer coerceOptionalInt(Object value) {
        if (value == null) return null;
        if (value instanceof String s && s.isBlank()) return null;
        try {
            if (value instanceof Number n) return n.intValue();
            if (value instanceof String s) return Integer.parseInt(s);
        } catch (Exception e) { return null; }
        return null;
    }

    public static boolean coerceBool(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            String lower = s.strip().toLowerCase();
            if (lower.equals("1") || lower.equals("true") || lower.equals("yes") || lower.equals("on")) return true;
            if (lower.equals("0") || lower.equals("false") || lower.equals("no") || lower.equals("off")) return false;
        }
        return value != null || defaultValue;
    }

    @SuppressWarnings("unchecked")
    public static List<String> coerceStrList(Object value) {
        if (value instanceof String s) return s.isEmpty() ? List.of() : List.of(s);
        if (value instanceof List<?> l) {
            return l.stream().map(Object::toString).filter(s -> !((String) s).isEmpty()).toList();
        }
        return List.of();
    }

    /**
     * Python first_content_line — first non-empty, non-heading, non-frontmatter body line.
     */
    public static String firstContentLine(String body, int limit) {
        if (body == null) return "";
        for (String line : body.split("\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.equals("---") && !stripped.startsWith("#")) {
                return stripped.length() > limit ? stripped.substring(0, limit) : stripped;
            }
        }
        return "";
    }

    public static String firstContentLine(String body) {
        return firstContentLine(body, 200);
    }
}
