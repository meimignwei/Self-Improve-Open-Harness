package com.openharness.extensions.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.ContentBlock;
import com.openharness.common.ConversationMessage;
import com.openharness.config.MemorySettings;
import com.openharness.config.Paths;
import com.openharness.extensions.memory.MemoryManager;
import com.openharness.extensions.memory.MemoryRelevance;
import com.openharness.extensions.memory.MemoryScanner;
import com.openharness.extensions.memory.MemoryTeamService;
import com.openharness.extensions.memory.MemoryType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts durable memories from conversation turns using LLM.
 * Java equivalent of Python services/memory_extract/__init__.py.
 */
public class MemoryExtractionService {

    private static final Set<String> MEMORY_WRITE_TOOLS = Set.of("write_file", "edit_file");

    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "ls", "pwd", "cat", "head", "tail", "rg", "grep", "find", "git", "wc", "sed", "awk", "stat");

    private static final Set<String> DENIED_SHELL_MARKERS = Set.of(
            " > ", ">>", " rm ", " mv ", " cp ", " sed -i", " tee ", "python -c", "python3 -c");

    /** Plain ObjectMapper for internal JSON parsing (no polymorphic typing). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Python: EXTRACTION_SYSTEM_PROMPT ──

    public static final String EXTRACTION_SYSTEM_PROMPT = """
            You maintain OpenHarness durable memory.
            Save only stable, future-useful facts that are not derivable from current files,
            git history, or documentation. Prefer updating existing memories conceptually
            over duplicating them. Do not save secrets. If nothing is worth saving, return
            {"memories": []}.
            """;

    // ── Python: ExtractionRecord / ExtractionResult ──

    public record ExtractionRecord(
            String title,
            String body,
            MemoryType memoryType,
            String scope,
            String description,
            List<String> tags
    ) {
        public ExtractionRecord {
            if (memoryType == null) memoryType = MemoryType.PROJECT;
            if (scope == null || scope.isBlank()) scope = "project";
            if (description == null) description = "";
            if (tags == null) tags = List.of();
        }
    }

    public record ExtractionResult(
            boolean skipped,
            String reason,
            List<ExtractionRecord> records,
            List<Path> writtenPaths
    ) {
        public ExtractionResult {
            if (reason == null) reason = "";
            if (records == null) records = List.of();
            if (writtenPaths == null) writtenPaths = List.of();
        }
    }

    // ── Python: has_memory_writes_since ──

    /**
     * Return whether the visible turn already wrote memory files.
     */
    public boolean hasMemoryWritesSince(List<ConversationMessage> messages,
                                        Path memoryDir, Path cwd) {
        Path root = memoryDir.toAbsolutePath().normalize();
        Path writeBase = cwd != null ? cwd.toAbsolutePath().normalize() : root;
        for (ConversationMessage message : messages) {
            for (ContentBlock block : message.content()) {
                if (!(block instanceof ContentBlock.ToolUseBlock tub)) continue;
                if (!MEMORY_WRITE_TOOLS.contains(tub.name())) continue;
                String rawPath = extractPath(tub.input());
                if (rawPath == null || rawPath.isEmpty()) continue;
                Path path = Path.of(rawPath);
                if (!path.isAbsolute()) {
                    path = writeBase.resolve(path);
                }
                try {
                    Path resolved = path.toAbsolutePath().normalize();
                    if (resolved.startsWith(root)) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    // ── Python: extract_memories_from_turn ──

    /**
     * Ask the model for durable memory candidates and apply them.
     *
     * @param cwd        working directory
     * @param llmCall    function that takes systemPrompt and userPrompt, returns response text
     * @param messages   recent conversation messages
     * @param maxRecords max records to extract (default 3)
     */
    public ExtractionResult extractMemoriesFromTurn(
            Path cwd,
            java.util.function.BiFunction<String, String, String> llmCall,
            List<ConversationMessage> messages,
            int maxRecords) {
        Path memoryDir = Paths.projectMemoryDir(cwd);
        if (messages.size() < 2) {
            return new ExtractionResult(true, "not enough messages", List.of(), List.of());
        }
        if (hasMemoryWritesSince(messages, memoryDir, cwd)) {
            return new ExtractionResult(true, "main conversation already wrote memory", List.of(), List.of());
        }

        String prompt = buildExtractionPrompt(cwd, messages, maxRecords);
        String finalText = llmCall.apply(EXTRACTION_SYSTEM_PROMPT, prompt);
        List<ExtractionRecord> records = parseExtractionRecords(finalText, maxRecords);
        if (records.isEmpty()) {
            return new ExtractionResult(true, "no durable memories proposed", List.of(), List.of());
        }
        return applyExtractionRecords(cwd, records);
    }

    public ExtractionResult extractMemoriesFromTurn(
            Path cwd,
            java.util.function.BiFunction<String, String, String> llmCall,
            List<ConversationMessage> messages) {
        return extractMemoriesFromTurn(cwd, llmCall, messages, 3);
    }

    // ── Python: build_extraction_prompt ──

    /**
     * Build the extraction request from recent messages and manifest.
     */
    String buildExtractionPrompt(Path cwd, List<ConversationMessage> messages, int maxRecords) {
        Path memoryDir = Paths.projectMemoryDir(cwd);
        String manifest = MemoryRelevance.buildMemoryManifest(
                MemoryScanner.scanMemoryFiles(memoryDir, 80));
        StringBuilder transcript = new StringBuilder();
        List<ConversationMessage> recent = messages.size() > 12
                ? messages.subList(messages.size() - 12, messages.size())
                : messages;
        for (ConversationMessage message : recent) {
            transcript.append(summarizeMessage(message)).append("\n");
        }
        return "Extract only durable memories from the recent conversation.\n"
                + "Return JSON with at most " + maxRecords + " records. Existing memory manifest:\n"
                + (!manifest.isEmpty() ? manifest : "(empty)") + "\n\n"
                + "Recent conversation:\n"
                + transcript + "\n"
                + "JSON schema: {\"memories\":[{\"title\":\"...\",\"type\":\"user|feedback|project|reference\","
                + "\"scope\":\"private|project|team\",\"description\":\"...\",\"body\":\"...\",\"tags\":[\"...\"]}]}";
    }

    // ── Python: parse_extraction_records ──

    @SuppressWarnings("unchecked")
    List<ExtractionRecord> parseExtractionRecords(String text, int maxRecords) {
        try {
            Map<String, Object> payload = MAPPER.readValue(
                    extractJsonObject(text), Map.class);
            Object rawRecords = payload.get("memories");
            if (!(rawRecords instanceof List<?> list)) return List.of();

            List<ExtractionRecord> records = new ArrayList<>();
            int limit = Math.min(list.size(), maxRecords);
            for (int i = 0; i < limit; i++) {
                Object item = list.get(i);
                if (!(item instanceof Map<?, ?> m)) continue;
                String title = strVal(m.get("title"), "").strip();
                String body = strVal(m.get("body"), "").strip();
                if (title.isEmpty() || body.isEmpty()) continue;
                MemoryType memoryType = parseMemoryType(strVal(m.get("type"), "project"));
                String scope = parseScope(strVal(m.get("scope"), "project"));
                String description = strVal(m.get("description"), "").strip();
                List<String> tags = new ArrayList<>();
                Object tagsRaw = m.get("tags");
                if (tagsRaw instanceof List<?> tagList) {
                    for (Object tag : tagList) {
                        String t = strVal(tag, "").strip();
                        if (!t.isEmpty()) tags.add(t);
                    }
                }
                records.add(new ExtractionRecord(title, body, memoryType, scope, description, tags));
            }
            return records;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Python: apply_extraction_records ──

    ExtractionResult applyExtractionRecords(Path cwd, List<ExtractionRecord> records) {
        List<Path> written = new ArrayList<>();
        MemorySettings settings = new MemorySettings();
        for (ExtractionRecord record : records) {
            if ("team".equals(record.scope())) {
                String secretError = MemoryTeamService.checkTeamMemorySecrets(record.body());
                if (secretError != null) continue;
                var validation = MemoryTeamService.validateTeamMemoryWritePath(
                        cwd, record.title() + ".md");
                if (validation.getValue() != null || validation.getKey() == null) continue;
            }
            Path memoryDir = Paths.projectMemoryDir(cwd);
            MemoryManager manager = new MemoryManager(memoryDir, settings);
            written.add(manager.addMemoryEntry(
                    cwd, record.title(), record.body(),
                    record.memoryType(), record.scope(),
                    record.description(), record.tags()));
        }
        return new ExtractionResult(
                written.isEmpty(),
                written.isEmpty() ? "all records rejected" : "",
                records,
                written);
    }

    // ── Python: validate_extraction_tool_request ──

    /**
     * Permission guard for extraction-like agents.
     * Returns (allowed, errorMessage).
     */
    public static java.util.AbstractMap.SimpleEntry<Boolean, String> validateExtractionToolRequest(
            String toolName, Map<String, Object> toolInput, Path memoryDir) {
        if (Set.of("read_file", "grep", "glob").contains(toolName)) {
            return new java.util.AbstractMap.SimpleEntry<>(true, "");
        }
        if ("bash".equals(toolName)) {
            String command = strVal(toolInput.get("command"), "");
            if (isReadOnlyShell(command)) {
                return new java.util.AbstractMap.SimpleEntry<>(true, "");
            }
            return new java.util.AbstractMap.SimpleEntry<>(false,
                    "memory extraction may only run read-only shell commands");
        }
        if (Set.of("write_file", "edit_file").contains(toolName)) {
            String rawPath = strVal(toolInput.get("path"),
                    strVal(toolInput.get("file_path"), ""));
            if (rawPath.isEmpty()) {
                return new java.util.AbstractMap.SimpleEntry<>(false,
                        "memory extraction write requires a path");
            }
            Path root = memoryDir.toAbsolutePath().normalize();
            Path path = Path.of(rawPath);
            if (!path.isAbsolute()) {
                path = root.resolve(rawPath);
            }
            try {
                if (!path.toAbsolutePath().normalize().startsWith(root)) {
                    return new java.util.AbstractMap.SimpleEntry<>(false,
                            "memory extraction writes must stay within " + root);
                }
            } catch (Exception e) {
                return new java.util.AbstractMap.SimpleEntry<>(false,
                        "memory extraction writes must stay within " + root);
            }
            return new java.util.AbstractMap.SimpleEntry<>(true, "");
        }
        return new java.util.AbstractMap.SimpleEntry<>(false,
                "memory extraction cannot use tool " + toolName);
    }

    // ── Python: _extract_json_object ──

    static String extractJsonObject(String text) {
        String stripped = text.strip();
        if (stripped.startsWith("{") && stripped.endsWith("}")) {
            return stripped;
        }
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return stripped.substring(start, end + 1);
        }
        return stripped;
    }

    // ── Python: _summarize_message ──

    static String summarizeMessage(ConversationMessage message) {
        String text = message.text();
        if (text != null && !text.isBlank()) {
            String collapsed = text.replaceAll("\\s+", " ").strip();
            String role = message.role() != null ? message.role().name().toLowerCase() : "unknown";
            return role + ": " + (collapsed.length() > 1200 ? collapsed.substring(0, 1200) : collapsed);
        }
        List<ContentBlock.ToolUseBlock> toolUses = message.toolUses();
        if (!toolUses.isEmpty()) {
            String role = message.role() != null ? message.role().name().toLowerCase() : "unknown";
            String names = toolUses.stream()
                    .map(ContentBlock.ToolUseBlock::name)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            return role + ": tool calls -> " + names;
        }
        String role = message.role() != null ? message.role().name().toLowerCase() : "unknown";
        return role + ": [non-text content]";
    }

    // ── Python: _is_read_only_shell ──

    static boolean isReadOnlyShell(String command) {
        String lowered = command.strip().toLowerCase();
        if (lowered.isEmpty()) return false;
        String padded = " " + lowered + " ";
        for (String marker : DENIED_SHELL_MARKERS) {
            if (padded.contains(marker)) return false;
        }
        String first = lowered.split("\\s+", 2)[0];
        return READ_ONLY_COMMANDS.contains(first);
    }

    // ── helpers ──

    private static String extractPath(JsonNode input) {
        if (input == null) return null;
        if (input.has("path") && !input.get("path").isNull()) {
            return input.get("path").asText();
        }
        if (input.has("file_path") && !input.get("file_path").isNull()) {
            return input.get("file_path").asText();
        }
        return null;
    }

    private static MemoryType parseMemoryType(String s) {
        if (s == null || s.isEmpty()) return MemoryType.PROJECT;
        return switch (s.strip().toLowerCase()) {
            case "user" -> MemoryType.USER;
            case "feedback" -> MemoryType.FEEDBACK;
            case "project" -> MemoryType.PROJECT;
            case "reference" -> MemoryType.REFERENCE;
            default -> MemoryType.PROJECT;
        };
    }

    private static String parseScope(String s) {
        if (s == null || s.isBlank()) return "project";
        String lowered = s.strip().toLowerCase();
        return switch (lowered) {
            case "private" -> "private";
            case "project" -> "project";
            case "team" -> "team";
            default -> "project";
        };
    }

    private static String strVal(Object val, String defaultValue) {
        if (val == null) return defaultValue != null ? defaultValue : "";
        String s = val.toString().strip();
        return s.isEmpty() && defaultValue != null && !defaultValue.isEmpty() ? defaultValue : s;
    }
}
