package com.openharness.extensions.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.ConversationMessage;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;
import com.openharness.common.UsageSnapshot;
import com.openharness.config.AtomicFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Session snapshot persistence: latest.json + session-<id>.json.
 * Java equivalent of Python services/session_storage.py.
 */
public class SessionStorage {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    private final Path sessionDir;

    public SessionStorage(Path sessionDir) {
        this.sessionDir = sessionDir;
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create session dir", e);
        }
    }

    public void saveSnapshot(String sessionId, SessionSnapshot snapshot) {
        Path sessionFile = sessionDir.resolve("session-" + sessionId + ".json");
        AtomicFileWriter.writeJson(sessionFile, snapshot);

        Path latestFile = sessionDir.resolve("latest.json");
        AtomicFileWriter.writeJson(latestFile, snapshot);
    }

    public Optional<SessionSnapshot> loadLatestSnapshot() {
        Path latestFile = sessionDir.resolve("latest.json");
        if (!Files.exists(latestFile)) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(latestFile.toFile(), SessionSnapshot.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<SessionSnapshot> loadById(String sessionId) {
        Path sessionFile = sessionDir.resolve("session-" + sessionId + ".json");
        if (!Files.exists(sessionFile)) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(sessionFile.toFile(), SessionSnapshot.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public List<SessionSnapshot> listSnapshots() {
        List<SessionSnapshot> snapshots = new ArrayList<>();
        try (var files = Files.list(sessionDir)) {
            files.filter(f -> {
                String name = f.getFileName().toString();
                return name.startsWith("session-") && name.endsWith(".json");
            }).forEach(f -> {
                try {
                    snapshots.add(MAPPER.readValue(f.toFile(), SessionSnapshot.class));
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        return snapshots;
    }

    public String exportMarkdown(String sessionId) {
        Optional<SessionSnapshot> opt = loadById(sessionId);
        if (opt.isEmpty()) return "";

        SessionSnapshot snapshot = opt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("# Session: ").append(sessionId).append("\n\n");
        sb.append("Started: ").append(snapshot.startedAt()).append("\n\n");

        for (SessionSnapshot.TurnRecord turn : snapshot.turns()) {
            sb.append("## Turn ").append(turn.index()).append("\n\n");
            sb.append(turn.userPrompt()).append("\n\n");
            sb.append(turn.assistantResponse()).append("\n\n");
        }

        return sb.toString();
    }

    public record SessionSnapshot(
            String sessionId,
            String cwd,
            String model,
            Instant startedAt,
            Instant updatedAt,
            List<TurnRecord> turns,
            UsageSnapshot totalUsage
    ) {
        public record TurnRecord(int index, String userPrompt, String assistantResponse,
                                  UsageSnapshot usage, List<ToolCallRecord> toolCalls) {}
        public record ToolCallRecord(String name, String arguments, ToolResult result) {}
    }
}
