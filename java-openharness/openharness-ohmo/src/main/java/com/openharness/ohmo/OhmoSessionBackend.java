package com.openharness.ohmo;

import com.openharness.common.UsageSnapshot;
import com.openharness.config.AtomicFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Persists session history by session_key with latest and per-session dumps.
 * Java equivalent of Python ohmo/session_storage.py.
 */
public class OhmoSessionBackend {

    private final Path sessionsDir;

    public OhmoSessionBackend(Path workspaceRoot) {
        this.sessionsDir = workspaceRoot.resolve("sessions");
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create sessions dir", e);
        }
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    public Path saveSnapshot(String cwd, String model, String systemPrompt,
                              List<?> messages, UsageSnapshot usage,
                              String sessionId, String sessionKey,
                              Map<String, Object> toolMetadata) {
        String sid = (sessionId != null && !sessionId.isEmpty()) ? sessionId : UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        double now = Instant.now().toEpochMilli() / 1000.0;

        String summary = "";
        for (var msg : messages) {
            if (msg instanceof Map<?, ?> m && "user".equals(m.get("role"))) {
                String text = (String) m.get("text");
                if (text != null && !text.isBlank()) {
                    summary = text.strip().length() > 80 ? text.strip().substring(0, 80) : text.strip();
                    break;
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("app", "ohmo");
        payload.put("session_id", sid);
        payload.put("session_key", sessionKey);
        payload.put("cwd", cwd);
        payload.put("model", model);
        payload.put("system_prompt", systemPrompt);
        payload.put("messages", messages);
        payload.put("usage", Map.of("input_tokens", usage.inputTokens(), "output_tokens", usage.outputTokens()));
        payload.put("tool_metadata", toolMetadata != null ? new LinkedHashMap<>(toolMetadata) : Map.of());
        payload.put("created_at", now);
        payload.put("summary", summary);
        payload.put("message_count", messages.size());

        AtomicFileWriter.writeJson(sessionsDir.resolve("latest.json"), payload);
        if (sessionKey != null && !sessionKey.isEmpty()) {
            AtomicFileWriter.writeJson(sessionKeyLatestPath(sessionKey), payload);
        }
        AtomicFileWriter.writeJson(sessionsDir.resolve("session-" + sid + ".json"), payload);
        return sessionsDir.resolve("latest.json");
    }

    public void save(String sessionKey, String sessionId, Object messages, Map<String, Object> toolMetadata) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_key", sessionKey);
        data.put("session_id", sessionId);
        data.put("messages", messages);
        data.put("tool_metadata", toolMetadata != null ? toolMetadata : Map.of());
        data.put("updated_at", Instant.now().toString());
        AtomicFileWriter.writeJson(sessionsDir.resolve(sanitizeKey(sessionKey) + ".json"), data);
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadLatest() {
        Path path = sessionsDir.resolve("latest.json");
        if (!Files.exists(path)) return null;
        try {
            return AtomicFileWriter.readJson(path, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadLatestForSessionKey(String sessionKey) {
        Path path = sessionKeyLatestPath(sessionKey);
        if (Files.exists(path)) {
            try {
                return AtomicFileWriter.readJson(path, Map.class);
            } catch (Exception e) {
                return null;
            }
        }
        return loadLatest();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadBySessionId(String sessionId) {
        Path path = sessionsDir.resolve("session-" + sessionId + ".json");
        if (Files.exists(path)) {
            try {
                return AtomicFileWriter.readJson(path, Map.class);
            } catch (Exception e) {
                return null;
            }
        }
        Map<String, Object> latest = loadLatest();
        if (latest != null && sessionId.equals(latest.get("session_id"))) {
            return latest;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSnapshots(int limit) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        try (Stream<Path> files = Files.list(sessionsDir)) {
            files.filter(f -> f.getFileName().toString().startsWith("session-")
                            && f.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> {
                        try {
                            return Long.compare(Files.getLastModifiedTime(b).toMillis(),
                                    Files.getLastModifiedTime(a).toMillis());
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .forEach(path -> {
                        if (sessions.size() >= limit) return;
                        try {
                            Map<String, Object> data = AtomicFileWriter.readJson(path, Map.class);
                            if (data != null) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("session_id", data.getOrDefault("session_id",
                                        path.getFileName().toString().replace("session-", "").replace(".json", "")));
                                entry.put("summary", data.getOrDefault("summary", ""));
                                entry.put("message_count", data.getOrDefault("message_count",
                                        data.containsKey("messages") ? ((List<?>) data.get("messages")).size() : 0));
                                entry.put("model", data.getOrDefault("model", ""));
                                entry.put("created_at", data.getOrDefault("created_at", 0));
                                sessions.add(entry);
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (IOException ignored) {}
        return sessions;
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Path exportMarkdown(List<?> messages) {
        Path path = sessionsDir.resolve("transcript.md");
        StringBuilder sb = new StringBuilder("# ohmo Session Transcript\n");
        for (var msg : messages) {
            if (msg instanceof Map<?, ?> m) {
                Object roleObj = m.get("role");
                String role = roleObj != null ? String.valueOf(roleObj) : "unknown";
                sb.append("\n## ").append(capitalize(role)).append("\n");
                Object textObj = m.get("text");
                String text = textObj instanceof String s ? s : null;
                if (text != null && !text.isBlank()) {
                    sb.append("\n").append(text.strip()).append("\n");
                }
            }
        }
        try {
            Files.writeString(path, sb.toString().stripTrailing() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to export session markdown", e);
        }
        return path;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    public void delete(String sessionKey) {
        try {
            Files.deleteIfExists(sessionsDir.resolve(sanitizeKey(sessionKey) + ".json"));
        } catch (IOException ignored) {}
    }

    private Path sessionKeyLatestPath(String sessionKey) {
        String token = sessionKeyToken(sessionKey);
        return sessionsDir.resolve("latest-" + token + ".json");
    }

    private String sessionKeyToken(String sessionKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(sessionKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sessionKey.hashCode());
        }
    }

    private static String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9_:-]", "_");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
