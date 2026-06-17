package com.openharness.extensions.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File-based memory persistence using YAML frontmatter + markdown body format.
 * Java equivalent of Python memory/manager.py + memory/schema.py.
 *
 * File format (18 frontmatter fields, stable order):
 * ---
 * schema_version: 1
 * id: "mem-20260617-120000-a1b2c3d4"
 * name: "Title"
 * description: "one-line description"
 * type: "project"
 * scope: "project"
 * category: "knowledge"
 * importance: 5
 * source: "manual"
 * signature: "sha256hex..."
 * created_at: "2026-06-17T12:00:00Z"
 * updated_at: "2026-06-17T12:00:00Z"
 * ttl_days: null
 * disabled: false
 * supersedes: []
 * tags: []
 * ---
 * Body markdown content
 */
public class MemoryFileStore {

    private static final String FRONTMATTER_DELIM = "---";

    /**
     * Python FRONTMATTER_FIELDS — stable ordering for serialization.
     */
    private static final List<String> FRONTMATTER_FIELDS = List.of(
            "schema_version", "id", "name", "description", "type", "scope",
            "category", "importance", "source", "signature",
            "created_at", "updated_at", "ttl_days", "disabled", "supersedes", "tags"
    );

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Python: MAX_ENTRYPOINT_LINES = 200 */
    public static final int MAX_ENTRYPOINT_LINES = 200;
    /** Python: MAX_ENTRYPOINT_BYTES = 25_000 */
    public static final int MAX_ENTRYPOINT_BYTES = 25_000;

    private static final String ENTRYPOINT_NAME = "MEMORY.md";
    private static final String ENTRYPOINT_HEADER = "# Memory Index";

    private final Path memoryDir;

    public MemoryFileStore(Path memoryDir) {
        this.memoryDir = memoryDir;
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory dir: " + memoryDir, e);
        }
    }

    public Path memoryDir() {
        return memoryDir;
    }

    // ------------------------------------------------------------------
    // Slug-based file path resolution
    // ------------------------------------------------------------------

    /**
     * Python: slug = sub(r"[^a-zA-Z0-9]+", "_", title.strip().lower()).strip("_") or "memory"
     */
    public static String toSlug(String title) {
        if (title == null || title.isBlank()) return "memory";
        String slug = title.strip().toLowerCase().replaceAll("[^a-zA-Z0-9]+", "_");
        return slug.replaceAll("^_+|_+$", "").isEmpty() ? "memory" : slug.replaceAll("^_+|_+$", "");
    }

    /**
     * Python _next_memory_path: {slug}.md, then {slug}_2.md, {slug}_3.md, ...
     */
    public Path resolveNextPath(String slug) {
        Path path = memoryDir.resolve(slug + ".md");
        if (!Files.exists(path)) return path;
        int index = 2;
        while (true) {
            Path candidate = memoryDir.resolve(slug + "_" + index + ".md");
            if (!Files.exists(candidate)) return candidate;
            index++;
        }
    }

    /**
     * Resolve file path for a memory entry using its id.
     * Scans for the id in frontmatter to find the file.
     */
    public Path resolvePathById(String id) {
        Path direct = memoryDir.resolve(id + ".md");
        if (Files.exists(direct)) return direct;
        // Search through all memory files for matching id
        try (Stream<Path> files = Files.list(memoryDir)) {
            return files.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".md") && !"MEMORY.md".equals(name);
            }).filter(f -> {
                try {
                    Map<String, Object> fm = parseFrontmatter(Files.readString(f, StandardCharsets.UTF_8));
                    return id.equals(fm.get("id"));
                } catch (Exception e) {
                    return false;
                }
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    public List<MemoryEntry> loadAll() {
        List<MemoryEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".md") && !"MEMORY.md".equals(name);
            }).forEach(f -> {
                try {
                    entries.add(parseMemoryFile(f));
                } catch (Exception e) {
                    System.err.println("Failed to parse memory file: " + f + " - " + e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list memory files", e);
        }
        return entries;
    }

    public MemoryEntry loadById(String id) {
        Path file = resolvePathById(id);
        if (file == null) return null;
        return parseMemoryFile(file);
    }

    // ------------------------------------------------------------------
    // Save / Delete
    // ------------------------------------------------------------------

    /**
     * Save using slug-based naming. Updates in-place if entry id already exists.
     */
    public Path save(MemoryEntry entry) {
        // Check if entry with this id already exists — update in place
        Path existingPath = resolvePathById(entry.header().id());
        if (existingPath != null) {
            saveAs(entry, existingPath);
            return existingPath;
        }
        String slug = toSlug(entry.header().name());
        Path file = resolveNextPath(slug);
        saveAs(entry, file);
        return file;
    }

    /**
     * Save to a specific path (for updates where we know the existing path).
     */
    public void saveAs(MemoryEntry entry, Path file) {
        String content = renderMemoryFile(entry);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tempPath = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tempPath, content, StandardCharsets.UTF_8);
            Files.move(tempPath, file,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save memory: " + file, e);
        }
    }

    public boolean delete(String id) {
        Path file = resolvePathById(id);
        if (file == null) return false;
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete memory: " + id, e);
        }
    }

    public boolean exists(String id) {
        return resolvePathById(id) != null;
    }

    // ------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------

    public List<MemoryEntry> findByType(MemoryType type) {
        return loadAll().stream()
                .filter(e -> e.header().type() == type)
                .toList();
    }

    public List<MemoryEntry> findBySignature(String signature) {
        return loadAll().stream()
                .filter(e -> e.header().signature().equals(signature))
                .toList();
    }

    // ------------------------------------------------------------------
    // Parse
    // ------------------------------------------------------------------

    public MemoryEntry parseMemoryFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return parseMarkdown(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read memory file: " + file, e);
        }
    }

    /**
     * Python split_memory_file: returns (metadata, body, body_start_line, has_closed_frontmatter).
     */
    public Map<String, Object> parseFrontmatter(String content) {
        if (content == null || !content.strip().startsWith(FRONTMATTER_DELIM)) {
            return new LinkedHashMap<>();
        }
        String[] lines = content.split("\n", -1);
        if (lines.length < 2 || !lines[0].strip().equals(FRONTMATTER_DELIM)) {
            return new LinkedHashMap<>();
        }
        StringBuilder raw = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals(FRONTMATTER_DELIM)) {
                return parseYamlFrontmatter(raw.toString());
            }
            raw.append(lines[i]).append("\n");
        }
        return new LinkedHashMap<>();
    }

    MemoryEntry parseMarkdown(String content) {
        Map<String, Object> fm = parseFrontmatter(content);
        String body = extractBody(content);

        int schemaVersion = MemoryEntry.coerceInt(fm.get("schema_version"), MemoryEntry.SCHEMA_VERSION);
        String id = strVal(fm.get("id"), MemoryEntry.generateMemoryId());
        String name = strVal(fm.get("name"), "");
        String description = strVal(fm.get("description"), "");
        MemoryType type = parseType(strVal(fm.get("type"), "project"));
        String scope = strVal(fm.get("scope"), "project");
        String category = strVal(fm.get("category"), "knowledge");
        int importance = MemoryEntry.coerceInt(fm.get("importance"), 5);
        String source = strVal(fm.get("source"), null);
        String signature = strVal(fm.get("signature"),
                MemorySignature.compute(body, type.name().toLowerCase(), category));
        Instant createdAt = parseInstant(fm.get("created_at"));
        Instant updatedAt = parseInstant(fm.get("updated_at"));
        Integer ttlDays = MemoryEntry.coerceOptionalInt(fm.get("ttl_days"));
        boolean disabled = MemoryEntry.coerceBool(fm.get("disabled"), false);

        @SuppressWarnings("unchecked")
        List<String> supersedes = fm.get("supersedes") instanceof List<?> l
                ? l.stream().map(Object::toString).toList() : MemoryEntry.coerceStrList(fm.get("supersedes"));

        @SuppressWarnings("unchecked")
        List<String> tags = fm.get("tags") instanceof List<?> l
                ? l.stream().map(Object::toString).toList() : MemoryEntry.coerceStrList(fm.get("tags"));

        MemoryEntry.MemoryHeader header = new MemoryEntry.MemoryHeader(
                schemaVersion, id, name, description, type, scope, category,
                importance, source, signature, createdAt, updatedAt,
                ttlDays, disabled, supersedes, tags);

        return new MemoryEntry(header, body);
    }

    // ------------------------------------------------------------------
    // Serialize — matching Python render_memory_file / render_frontmatter
    // ------------------------------------------------------------------

    /**
     * Python render_memory_file(metadata, body).
     */
    String renderMemoryFile(MemoryEntry entry) {
        Map<String, Object> fm = frontmatterToMap(entry.header());
        String frontmatter = FRONTMATTER_DELIM + "\n" + renderFrontmatter(fm) + FRONTMATTER_DELIM + "\n";
        String body = entry.body() != null ? entry.body().stripLeading() : "";
        if (!body.isEmpty() && !body.endsWith("\n")) body += "\n";
        return frontmatter + "\n" + body;
    }

    /**
     * Python render_frontmatter — stable field order.
     */
    String renderFrontmatter(Map<String, Object> metadata) {
        List<java.util.AbstractMap.SimpleEntry<String, Object>> ordered = new ArrayList<>();

        // Known fields in stable order (Python: if field in metadata)
        for (String field : FRONTMATTER_FIELDS) {
            if (metadata.containsKey(field)) {
                ordered.add(new java.util.AbstractMap.SimpleEntry<>(field, metadata.get(field)));
            }
        }
        // Unknown fields appended after
        for (var entry : metadata.entrySet()) {
            if (!FRONTMATTER_FIELDS.contains(entry.getKey())) {
                ordered.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
            }
        }

        StringBuilder sb = new StringBuilder();
        for (var entry : ordered) {
            sb.append(entry.getKey()).append(": ").append(formatYamlValue(entry.getValue())).append("\n");
        }
        return sb.toString();
    }

    /**
     * Frontmatter field map from MemoryHeader — all 18 fields.
     */
    private Map<String, Object> frontmatterToMap(MemoryEntry.MemoryHeader h) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("schema_version", h.schemaVersion());
        fm.put("id", h.id());
        fm.put("name", h.name() != null ? h.name() : "");
        fm.put("description", h.description() != null ? h.description() : "");
        fm.put("type", h.type().name().toLowerCase());
        fm.put("scope", h.scope() != null ? h.scope() : "project");
        fm.put("category", h.category() != null ? h.category() : "knowledge");
        fm.put("importance", h.importance());
        fm.put("source", h.source());
        fm.put("signature", h.signature());
        fm.put("created_at", formatInstant(h.createdAt()));
        fm.put("updated_at", formatInstant(h.updatedAt()));
        fm.put("ttl_days", h.ttlDays());
        fm.put("disabled", h.disabled());
        fm.put("supersedes", h.supersedes());
        fm.put("tags", h.tags());
        return fm;
    }

    // ------------------------------------------------------------------
    // YAML value formatting — matching Python _format_yaml_value
    // ------------------------------------------------------------------

    /**
     * Python _format_yaml_value:
     * - null → "null"
     * - bool → "true"/"false"
     * - int → str
     * - list/tuple → json.dumps(list(value), ensure_ascii=False)
     * - else → json.dumps(str(value), ensure_ascii=False)
     */
    static String formatYamlValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean b) return b ? "true" : "false";
        if (value instanceof Integer || value instanceof Long) return value.toString();
        if (value instanceof List<?> l) {
            try { return MAPPER.writeValueAsString(l); } catch (Exception e) { return "[]"; }
        }
        try {
            return MAPPER.writeValueAsString(value.toString());
        } catch (Exception e) {
            return "\"\"";
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static String extractBody(String content) {
        if (content == null || !content.strip().startsWith(FRONTMATTER_DELIM)) {
            return content != null ? content.strip() : "";
        }
        String[] lines = content.split("\n", -1);
        if (lines.length < 2) return "";
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals(FRONTMATTER_DELIM)) {
                StringBuilder body = new StringBuilder();
                for (int j = i + 1; j < lines.length; j++) {
                    body.append(lines[j]);
                    if (j < lines.length - 1) body.append("\n");
                }
                return body.toString().strip();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYamlFrontmatter(String raw) {
        try {
            // Use SnakeYAML for parsing YAML frontmatter
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> loaded = yaml.load(new StringReader(raw));
            if (loaded == null) return new LinkedHashMap<>();
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : loaded.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static MemoryType parseType(String s) {
        if (s == null) return MemoryType.PROJECT;
        String lowered = s.strip().toLowerCase();
        return switch (lowered) {
            case "user" -> MemoryType.USER;
            case "feedback" -> MemoryType.FEEDBACK;
            case "project" -> MemoryType.PROJECT;
            case "reference" -> MemoryType.REFERENCE;
            default -> MemoryType.PROJECT;
        };
    }

    private static String strVal(Object val, String defaultValue) {
        if (val == null) return defaultValue;
        String s = val.toString().strip();
        return s.isEmpty() && defaultValue != null ? defaultValue : s;
    }

    private static Instant parseInstant(Object val) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (val == null) return now;
        try {
            String s = val.toString().strip();
            if (s.endsWith("Z")) s = s.substring(0, s.length() - 1) + "+00:00";
            return Instant.parse(s).truncatedTo(ChronoUnit.SECONDS);
        } catch (Exception e) {
            return now;
        }
    }

    static String formatInstant(Instant instant) {
        if (instant == null) return "";
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    // ------------------------------------------------------------------
    // MEMORY.md index — matching Python manager.py
    // ------------------------------------------------------------------

    /**
     * Python: entrypoint = memory_dir / "MEMORY.md"
     */
    public Path getEntrypointPath() {
        return memoryDir.resolve(ENTRYPOINT_NAME);
    }

    /**
     * Python: get_memory_entrypoint(cwd)
     */
    public Path getEntrypointPath(Path cwd) {
        return memoryDir.resolve(ENTRYPOINT_NAME);
    }

    /**
     * Read MEMORY.md content.
     */
    public String getEntrypointContent() {
        Path entrypoint = getEntrypointPath();
        if (!Files.exists(entrypoint)) return "";
        try {
            return Files.readString(entrypoint, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Atomic append a line to MEMORY.md index.
     * Python: append "- [title](filename.md)" if not already present.
     */
    public void appendToIndex(String title, String filename) {
        try {
            Path entrypoint = getEntrypointPath();
            String indexText;
            if (Files.exists(entrypoint)) {
                indexText = Files.readString(entrypoint, StandardCharsets.UTF_8);
            } else {
                indexText = ENTRYPOINT_HEADER + "\n";
            }
            if (!indexText.contains(filename)) {
                indexText = indexText.stripTrailing() + "\n- [" + title + "](" + filename + ")\n";
                writeAtomic(entrypoint, indexText);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to MEMORY.md index", e);
        }
    }

    /**
     * Remove the line mentioning filename from MEMORY.md.
     * Python: filter out lines where path.name is in the line.
     */
    public void removeFromIndex(String filename) {
        try {
            Path entrypoint = getEntrypointPath();
            if (!Files.exists(entrypoint)) return;
            String text = Files.readString(entrypoint, StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            StringBuilder result = new StringBuilder();
            for (String line : lines) {
                if (!line.contains(filename)) {
                    result.append(line).append("\n");
                }
            }
            writeAtomic(entrypoint, result.toString().stripTrailing() + "\n");
        } catch (IOException e) {
            throw new RuntimeException("Failed to remove from MEMORY.md index", e);
        }
    }

    /**
     * Python truncate_entrypoint_content — bound by line count and UTF-8 byte count.
     * Returns (content, was_truncated, reason).
     */
    public static java.util.AbstractMap.SimpleEntry<String, TruncationInfo> truncateEntrypointContent(
            String raw, int maxLines, int maxBytes) {
        String[] lines = raw.split("\n", -1);
        boolean wasLineTruncated = lines.length > maxLines;
        String text = String.join("\n",
                java.util.Arrays.copyOf(lines, Math.min(lines.length, maxLines)));
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        boolean wasByteTruncated = encoded.length > maxBytes;
        if (wasByteTruncated) {
            byte[] truncated = java.util.Arrays.copyOf(encoded, maxBytes);
            text = new String(truncated, StandardCharsets.UTF_8);
            int cutAt = text.lastIndexOf('\n');
            if (cutAt > 0) text = text.substring(0, cutAt);
        }
        if (raw.endsWith("\n") && !text.endsWith("\n")) text += "\n";
        if (!wasLineTruncated && !wasByteTruncated) {
            return new java.util.AbstractMap.SimpleEntry<>(text,
                    new TruncationInfo(false, ""));
        }
        String reason = wasByteTruncated
                ? encoded.length + " bytes (limit: " + maxBytes + ")"
                : lines.length + " lines (limit: " + maxLines + ")";
        String warning = "\n\n> WARNING: MEMORY.md is " + reason
                + ". Only part of it was loaded. "
                + "Keep index entries one line and move detail into topic notes.\n";
        return new java.util.AbstractMap.SimpleEntry<>(
                text.stripTrailing() + warning,
                new TruncationInfo(true, reason));
    }

    /**
     * Convenience overload using default limits.
     */
    public static java.util.AbstractMap.SimpleEntry<String, TruncationInfo> truncateEntrypointContent(String raw) {
        return truncateEntrypointContent(raw, MAX_ENTRYPOINT_LINES, MAX_ENTRYPOINT_BYTES);
    }

    /**
     * Python atomic_write_text: write to temp then atomic rename.
     */
    private static void writeAtomic(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tempPath, content, StandardCharsets.UTF_8);
        Files.move(tempPath, path,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public record TruncationInfo(boolean wasTruncated, String reason) {}
}
