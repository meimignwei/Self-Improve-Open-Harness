package com.openharness.extensions.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * File-based memory persistence using YAML frontmatter + markdown body format.
 * Java equivalent of Python memory/memdir.py.
 *
 * File format:
 * ---
 * schema_version: 2
 * id: uuid
 * name: Title
 * description: one-line
 * type: USER
 * ...
 * ---
 * Body markdown content
 */
public class MemoryFileStore {

    private static final String FRONTMATTER_DELIM = "---";
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();
    private static final Yaml YAML;

    static {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        YAML = new Yaml(options);
    }

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

    public List<MemoryEntry> loadAll() {
        List<MemoryEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .forEach(f -> {
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
        Path file = memoryDir.resolve(id + ".md");
        if (!Files.exists(file)) {
            return null;
        }
        return parseMemoryFile(file);
    }

    public void save(MemoryEntry entry) {
        Path file = memoryDir.resolve(entry.header().id() + ".md");
        String content = serializeToMarkdown(entry);
        try {
            Path tempPath = memoryDir.resolve(entry.header().id() + ".tmp");
            Files.writeString(tempPath, content, StandardCharsets.UTF_8);
            Files.move(tempPath, file,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save memory: " + entry.header().id(), e);
        }
    }

    public boolean delete(String id) {
        Path file = memoryDir.resolve(id + ".md");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete memory: " + id, e);
        }
    }

    public boolean exists(String id) {
        return Files.exists(memoryDir.resolve(id + ".md"));
    }

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

    public MemoryEntry parseMemoryFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return parseMarkdown(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read memory file: " + file, e);
        }
    }

    MemoryEntry parseMarkdown(String content) {
        String frontmatter = "";
        String body = "";

        if (content.startsWith(FRONTMATTER_DELIM)) {
            int endIdx = content.indexOf(FRONTMATTER_DELIM, 3);
            if (endIdx > 0) {
                frontmatter = content.substring(3, endIdx).trim();
                body = content.substring(endIdx + 3).trim();
            }
        } else {
            body = content.trim();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fm = YAML.load(new StringReader(frontmatter));
        if (fm == null) {
            fm = new LinkedHashMap<>();
        }

        int schemaVersion = getInt(fm, "schema_version", 2);
        String id = getString(fm, "id", UUID.randomUUID().toString());
        String name = getString(fm, "name", "");
        String description = getString(fm, "description", "");
        MemoryType type = parseType(getString(fm, "type", "USER"));
        String category = getString(fm, "category", null);
        int importance = getInt(fm, "importance", 5);
        String source = getString(fm, "source", null);
        String signature = getString(fm, "signature", MemorySignature.compute(name, body));
        Instant createdAt = parseInstant(fm, "created_at");
        Instant updatedAt = parseInstant(fm, "updated_at");
        Integer ttlDays = fm.containsKey("ttl_days") ? getInt(fm, "ttl_days", 0) : null;
        boolean disabled = getBool(fm, "disabled", false);

        @SuppressWarnings("unchecked")
        List<String> supersedes = (List<String>) fm.getOrDefault("supersedes", List.of());

        MemoryEntry.MemoryHeader header = new MemoryEntry.MemoryHeader(
                schemaVersion, id, name, description, type, category,
                importance, source, signature, createdAt, updatedAt,
                ttlDays, disabled, supersedes);

        return new MemoryEntry(header, body);
    }

    String serializeToMarkdown(MemoryEntry entry) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("schema_version", entry.header().schemaVersion());
        fm.put("id", entry.header().id());
        fm.put("name", entry.header().name());
        fm.put("description", entry.header().description() != null ? entry.header().description() : "");
        fm.put("type", entry.header().type().name());
        if (entry.header().category() != null) fm.put("category", entry.header().category());
        fm.put("importance", entry.header().importance());
        if (entry.header().source() != null) fm.put("source", entry.header().source());
        fm.put("signature", entry.header().signature());
        fm.put("created_at", entry.header().createdAt().toString());
        fm.put("updated_at", entry.header().updatedAt().toString());
        if (entry.header().ttlDays() != null) fm.put("ttl_days", entry.header().ttlDays());
        if (entry.header().disabled()) fm.put("disabled", true);
        if (!entry.header().supersedes().isEmpty()) fm.put("supersedes", entry.header().supersedes());

        StringWriter writer = new StringWriter();
        writer.write(FRONTMATTER_DELIM + "\n");
        writer.write(YAML.dump(fm));
        writer.write(FRONTMATTER_DELIM + "\n");
        writer.write(entry.body() != null ? entry.body() : "");
        return writer.toString();
    }

    private static MemoryType parseType(String s) {
        try {
            return MemoryType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemoryType.USER;
        }
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    private static Instant parseInstant(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return Instant.now();
        try {
            return Instant.parse(val.toString());
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
