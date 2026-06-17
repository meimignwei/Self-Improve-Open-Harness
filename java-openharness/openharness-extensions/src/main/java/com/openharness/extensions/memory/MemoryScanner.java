package com.openharness.extensions.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans project memory files and returns MemoryScanHeader results.
 * Java equivalent of Python memory/scan.py.
 */
public class MemoryScanner {

    /**
     * Python scan_memory_files: glob *.md, skip MEMORY.md, parse frontmatter,
     * filter disabled/expired, sort by modified_at desc.
     */
    public static List<MemoryScanHeader> scanMemoryFiles(
            Path memoryDir, Integer maxFiles,
            boolean includeDisabled, boolean includeExpired) {
        List<MemoryScanHeader> headers = new ArrayList<>();
        if (!Files.exists(memoryDir)) return headers;

        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".md") && !"MEMORY.md".equals(name);
            }).forEach(f -> {
                try {
                    MemoryScanHeader header = parseMemoryFileScan(f);
                    if (header.disabled() && !includeDisabled) return;
                    if (isExpired(header) && !includeExpired) return;
                    headers.add(header);
                } catch (Exception e) {
                    // Skip unreadable files
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan memory files: " + memoryDir, e);
        }

        headers.sort(Comparator.comparing(MemoryScanHeader::modifiedAt).reversed());
        if (maxFiles == null) return headers;
        return headers.subList(0, Math.min(maxFiles, headers.size()));
    }

    public static List<MemoryScanHeader> scanMemoryFiles(Path memoryDir) {
        return scanMemoryFiles(memoryDir, 50, false, false);
    }

    public static List<MemoryScanHeader> scanMemoryFiles(Path memoryDir, Integer maxFiles) {
        return scanMemoryFiles(memoryDir, maxFiles, false, false);
    }

    /**
     * Python _parse_memory_file — extracts frontmatter, builds body_preview.
     */
    static MemoryScanHeader parseMemoryFileScan(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return parseScanHeader(file, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read memory file: " + file, e);
        }
    }

    static MemoryScanHeader parseScanHeader(Path file, String content) {
        MemoryFileStore store = new MemoryFileStore(file.getParent());
        Map<String, Object> metadata = store.parseFrontmatter(content);
        String body = extractBody(content);

        String title = strVal(metadata.get("name"), file.getFileName().toString().replaceAll("\\.md$", ""));
        String description = strVal(metadata.get("description"), "");
        String memoryType = strVal(metadata.get("type"), "");

        // Fallback: first non-empty, non-frontmatter line as description
        String descLine = null;
        if (description.isEmpty()) {
            descLine = MemoryEntry.firstContentLine(body, 200);
            if (!descLine.isBlank()) description = descLine;
        }

        // Build body preview from content, excluding the description line
        String[] bodyLines = body.split("\n", -1);
        StringBuilder preview = new StringBuilder();
        for (String line : bodyLines) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            if (stripped.length() > 0 && stripped.equals(descLine)) continue;
            if (!preview.isEmpty()) preview.append(" ");
            preview.append(stripped);
        }
        String bodyPreview = preview.length() > 300 ? preview.substring(0, 300) : preview.toString();

        float modifiedAt;
        try {
            modifiedAt = Files.getLastModifiedTime(file).toMillis() / 1000.0f;
        } catch (IOException e) {
            modifiedAt = Instant.now().getEpochSecond();
        }

        // Build full MemoryEntry.MemoryHeader
        MemoryEntry.MemoryHeader header = new MemoryEntry.MemoryHeader(
                MemoryEntry.coerceInt(metadata.get("schema_version"), MemoryEntry.SCHEMA_VERSION),
                strVal(metadata.get("id"), MemoryEntry.generateMemoryId()),
                title,
                description,
                parseType(strVal(metadata.get("type"), "project")),
                strVal(metadata.get("scope"), "project"),
                strVal(metadata.get("category"), "knowledge"),
                MemoryEntry.coerceInt(metadata.get("importance"), 0),
                strVal(metadata.get("source"), ""),
                strVal(metadata.get("signature"), ""),
                parseInstant(strVal(metadata.get("created_at"), null)),
                parseInstant(strVal(metadata.get("updated_at"), null)),
                MemoryEntry.coerceOptionalInt(metadata.get("ttl_days")),
                MemoryEntry.coerceBool(metadata.get("disabled"), false),
                MemoryEntry.coerceStrList(metadata.get("supersedes")),
                MemoryEntry.coerceStrList(metadata.get("tags")));

        return new MemoryScanHeader(file, header, modifiedAt, bodyPreview,
                file.getFileName().toString(), new LinkedHashMap<>(metadata));
    }

    private static boolean isExpired(MemoryScanHeader header) {
        return header.header().isExpired();
    }

    private static String extractBody(String content) {
        if (content == null || !content.strip().startsWith("---")) {
            return content != null ? content.strip() : "";
        }
        String[] lines = content.split("\n", -1);
        if (lines.length < 2) return "";
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
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

    private static MemoryType parseType(String s) {
        if (s == null || s.isEmpty()) return MemoryType.PROJECT;
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
        if (val == null) return defaultValue != null ? defaultValue : "";
        String s = val.toString().strip();
        return s.isEmpty() && defaultValue != null && !defaultValue.isEmpty() ? defaultValue : s;
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        try {
            String s = text.strip();
            if (s.endsWith("Z")) s = s.substring(0, s.length() - 1) + "+00:00";
            return Instant.parse(s).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        } catch (Exception e) {
            return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        }
    }
}
