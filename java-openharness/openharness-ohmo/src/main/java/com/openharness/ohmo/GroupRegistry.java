package com.openharness.ohmo;

import com.openharness.config.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Persistent metadata for ohmo-managed chat groups.
 * Java equivalent of Python ohmo/group_registry.py.
 */
public class GroupRegistry {

    private final Path groupsDir;

    public GroupRegistry(Path workspaceRoot) {
        this.groupsDir = workspaceRoot.resolve("groups");
        try {
            Files.createDirectories(groupsDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create groups dir", e);
        }
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    public Path saveRecord(String channel, String chatId, String ownerOpenId,
                           String name, String cwd, String repo,
                           String bindingStatus, Map<String, Object> metadata) {
        ManagedGroupRecord record = new ManagedGroupRecord(
                channel, chatId, ownerOpenId, normalizeGroupName(name),
                Instant.now().toString(),
                cwd != null ? normalizeCwd(cwd) : null,
                repo,
                bindingStatus != null ? bindingStatus : "pending_agent",
                metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>()
        );
        Path path = groupRecordPath(channel, chatId);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException ignored) {}
        AtomicFileWriter.writeJson(path, record.toMap());
        return path;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadRecord(String channel, String chatId) {
        Path path = groupRecordPath(channel, chatId);
        if (!Files.exists(path)) return null;
        try {
            return AtomicFileWriter.readJson(path, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    public List<Map<String, Object>> listRecords(String channel) {
        List<Map<String, Object>> records = new ArrayList<>();
        Path channelDir = groupsDir.resolve(channel);
        if (!Files.exists(channelDir)) return records;
        try (Stream<Path> files = Files.list(channelDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .forEach(f -> {
                        try {
                            Map<String, Object> record = AtomicFileWriter.readJson(f, Map.class);
                            if (record != null) records.add(record);
                        } catch (Exception ignored) {}
                    });
        } catch (IOException ignored) {}
        return records;
    }

    public boolean deleteRecord(String channel, String chatId) {
        try {
            return Files.deleteIfExists(groupRecordPath(channel, chatId));
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Path helpers
    // ------------------------------------------------------------------

    private Path groupRecordPath(String channel, String chatId) {
        String safe = SAFE_ID.matcher(chatId).replaceAll("_").replaceAll("^[._]+|[._]+$", "");
        if (safe.isEmpty()) safe = "unknown";
        return groupsDir.resolve(channel).resolve(safe + ".json");
    }

    private static final Pattern SAFE_ID = Pattern.compile("[^A-Za-z0-9_.-]+");

    // ------------------------------------------------------------------
    // Normalization
    // ------------------------------------------------------------------

    public static String normalizeGroupName(String raw) {
        String name = raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new IllegalArgumentException("Group name is required.");
        if (name.length() > 100) throw new IllegalArgumentException("Group name too long (max 100 chars).");
        return name;
    }

    public static String normalizeCwd(String cwd) {
        return Path.of(cwd).toAbsolutePath().normalize().toString();
    }

    // ------------------------------------------------------------------
    // Record type
    // ------------------------------------------------------------------

    public record ManagedGroupRecord(
            String channel, String chatId, String ownerOpenId, String name,
            String createdAt, String cwd, String repo, String bindingStatus,
            Map<String, Object> metadata) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("channel", channel);
            map.put("chat_id", chatId);
            map.put("owner_open_id", ownerOpenId);
            map.put("name", name);
            map.put("created_at", createdAt);
            if (cwd != null) map.put("cwd", cwd);
            if (repo != null) map.put("repo", repo);
            map.put("binding_status", bindingStatus);
            map.put("metadata", metadata);
            return map;
        }
    }
}