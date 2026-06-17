package com.openharness.extensions.services;

import com.openharness.common.ConversationMessage;
import com.openharness.common.ContentBlock;
import com.openharness.config.Paths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * File-backed session memory for compact continuity.
 * Matching Python's services/session_memory/__init__.py.
 */
public final class SessionMemoryService {

    private static final int MAX_SESSION_MEMORY_CHARS = 12_000;
    private static final int MAX_RECENT_LINES = 80;

    private SessionMemoryService() {}

    // ------------------------------------------------------------------
    // Path resolution
    // ------------------------------------------------------------------

    public static Path getSessionMemoryDir(Path cwd) {
        String absPath = cwd.toAbsolutePath().normalize().toString();
        String digest = sha1Hex(absPath).substring(0, 12);
        String dirName = cwd.toAbsolutePath().normalize().getFileName() + "-" + digest;
        Path dir = Paths.dataDir().resolve("session-memory").resolve(dirName);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create session memory directory: " + dir, e);
        }
        return dir;
    }

    public static Path getSessionMemoryPath(Path cwd, String sessionId) {
        String safe = (sessionId != null ? sessionId : "default")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        return getSessionMemoryDir(cwd).resolve(safe + ".md");
    }

    public static Path prepareSessionMemoryMetadata(Path cwd, Map<String, Object> toolMetadata, String sessionId) {
        String sid = sessionId != null ? sessionId
                : (toolMetadata != null ? Objects.toString(toolMetadata.get("session_id"), "default") : "default");
        Path path = getSessionMemoryPath(cwd, sid);
        if (toolMetadata != null) {
            toolMetadata.put("session_memory_path", path.toString());
        }
        return path;
    }

    // ------------------------------------------------------------------
    // Read / write
    // ------------------------------------------------------------------

    public static String getSessionMemoryContent(String path) {
        if (path == null || path.isEmpty()) return "";
        Path candidate = Path.of(path).toAbsolutePath().normalize();
        if (!Files.exists(candidate)) return "";
        try {
            return Files.readString(candidate, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static Path updateSessionMemoryFile(Path cwd, List<ConversationMessage> messages,
                                                Map<String, Object> toolMetadata, String sessionId) {
        Path path = prepareSessionMemoryMetadata(cwd, toolMetadata, sessionId);
        String body = buildSessionMemoryDocument(messages, toolMetadata);
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tempPath, body, StandardCharsets.UTF_8);
            Files.move(tempPath, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write session memory: " + path, e);
        }
        return path;
    }

    // ------------------------------------------------------------------
    // Document builder
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static String buildSessionMemoryDocument(List<ConversationMessage> messages,
                                                     Map<String, Object> toolMetadata) {
        String goal = "";
        String nextStep = "";
        List<String> verified = List.of();
        List<String> artifacts = List.of();

        if (toolMetadata != null) {
            Object state = toolMetadata.get("task_focus_state");
            if (state instanceof Map<?, ?> s) {
                goal = s.get("goal") instanceof String g ? g.strip() : "";
                nextStep = s.get("next_step") instanceof String ns ? ns.strip() : "";
                verified = stringList(s.get("verified_state"));
                artifacts = stringList(s.get("active_artifacts"));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Session Memory\n\n");
        sb.append("## Current State\n");
        sb.append(!goal.isEmpty() ? goal : "(no current goal recorded)");
        sb.append("\n\n");
        if (!nextStep.isEmpty()) {
            sb.append("## Next Step\n").append(nextStep).append("\n\n");
        }
        if (!verified.isEmpty()) {
            sb.append("## Verified Work\n");
            verified.stream().limit(10).forEach(v -> sb.append("- ").append(v).append("\n"));
            sb.append("\n");
        }
        if (!artifacts.isEmpty()) {
            sb.append("## Active Artifacts\n");
            artifacts.stream().limit(10).forEach(a -> sb.append("- ").append(a).append("\n"));
            sb.append("\n");
        }
        sb.append("## Recent Conversation\n");
        List<String> recentLines = recentMessageLines(messages);
        recentLines.forEach(l -> sb.append("- ").append(l).append("\n"));
        sb.append("\n");

        String text = sb.toString().strip() + "\n";
        if (text.length() > MAX_SESSION_MEMORY_CHARS) {
            int lastBreak = text.lastIndexOf('\n', MAX_SESSION_MEMORY_CHARS);
            if (lastBreak < 0) lastBreak = MAX_SESSION_MEMORY_CHARS;
            text = text.substring(0, lastBreak)
                    + "\n\n> Session memory was truncated to stay within budget.\n";
        }
        return text;
    }

    public static String sessionMemoryToCompactText(String content) {
        String stripped = content.strip();
        if (stripped.isEmpty()) return "";
        if (CompactionService.estimateTokens(stripped) > 4_000) {
            int lastBreak = stripped.lastIndexOf('\n', MAX_SESSION_MEMORY_CHARS);
            if (lastBreak < 0) lastBreak = Math.min(MAX_SESSION_MEMORY_CHARS, stripped.length());
            stripped = stripped.substring(0, lastBreak);
        }
        return "Session memory checkpoint from earlier in this conversation:\n" + stripped;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<String> recentMessageLines(List<ConversationMessage> messages) {
        List<ConversationMessage> recent = messages.size() > MAX_RECENT_LINES
                ? messages.subList(messages.size() - MAX_RECENT_LINES, messages.size())
                : messages;
        List<String> lines = new ArrayList<>();
        for (ConversationMessage msg : recent) {
            String line = summarizeMessage(msg);
            if (!line.isEmpty()) lines.add(line);
        }
        return lines.isEmpty() ? List.of("(no recent messages)") : lines;
    }

    private static String summarizeMessage(ConversationMessage message) {
        String text = String.join(" ", message.text().split("\\s+"));
        if (!text.isEmpty()) {
            text = text.length() > 220 ? text.substring(0, 220) : text;
            return message.role().name().toLowerCase() + ": " + text;
        }
        List<ContentBlock.ToolUseBlock> toolUses = message.toolUses();
        if (!toolUses.isEmpty()) {
            return message.role().name().toLowerCase() + ": tool calls -> "
                    + toolUses.stream().limit(6).map(ContentBlock.ToolUseBlock::name)
                    .collect(Collectors.joining(", "));
        }
        if (message.toolResults().stream().findAny().isPresent()) {
            return message.role().name().toLowerCase() + ": tool results returned";
        }
        return message.role().name().toLowerCase() + ": [non-text content]";
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object obj) {
        if (obj instanceof List<?> l) {
            return l.stream().map(Object::toString).map(String::strip)
                    .filter(s -> !s.isEmpty()).toList();
        }
        return List.of();
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}
