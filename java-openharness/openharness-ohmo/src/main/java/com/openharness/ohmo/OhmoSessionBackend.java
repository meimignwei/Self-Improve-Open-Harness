package com.openharness.ohmo;

import com.openharness.config.AtomicFileWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists session history by session_key.
 * Java equivalent of Python ohmo/session_storage.py.
 */
public class OhmoSessionBackend {

    private final Path sessionsDir;

    public OhmoSessionBackend(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        try {
            Files.createDirectories(sessionsDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create sessions dir", e);
        }
    }

    public void save(String sessionKey, String sessionId, Object messages, Map<String, Object> toolMetadata) {
        Path file = sessionsDir.resolve(sanitizeKey(sessionKey) + ".json");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_key", sessionKey);
        data.put("session_id", sessionId);
        data.put("messages", messages);
        data.put("tool_metadata", toolMetadata != null ? toolMetadata : Map.of());
        data.put("updated_at", Instant.now().toString());
        AtomicFileWriter.writeJson(file, data);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadLatestForSessionKey(String sessionKey) {
        Path file = sessionsDir.resolve(sanitizeKey(sessionKey) + ".json");
        if (!Files.exists(file)) return Map.of();
        try {
            return AtomicFileWriter.readJson(file, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public void delete(String sessionKey) {
        try {
            Files.deleteIfExists(sessionsDir.resolve(sanitizeKey(sessionKey) + ".json"));
        } catch (Exception ignored) {}
    }

    private String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9_:-]", "_");
    }
}
