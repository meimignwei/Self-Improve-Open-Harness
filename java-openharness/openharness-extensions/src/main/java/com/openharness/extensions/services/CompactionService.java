package com.openharness.extensions.services;

import com.openharness.api.StreamOptions;
import com.openharness.api.StreamingApiClient;
import com.openharness.common.ApiStreamEvent;
import com.openharness.common.ContentBlock;
import com.openharness.common.ConversationMessage;
import com.openharness.common.Role;
import com.openharness.engine.AutoCompactState;
import com.openharness.extensions.hooks.HookEvent;
import com.openharness.extensions.hooks.HookExecutor;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.openharness.extensions.services.CompactionConstants.*;

/**
 * Three-level auto-compaction strategy matching Python's services/compact/__init__.py.
 *
 * L1: MicroCompact — clear old compactable tool results cheaply
 * L2: Session Memory — deterministic per-message condensation
 * L3: Full LLM Compact — structured summarization via API
 */
public class CompactionService {

    private static final Logger LOG = Logger.getLogger(CompactionService.class.getName());

    // ------------------------------------------------------------------
    // Token estimation
    // ------------------------------------------------------------------

    /** Estimate tokens from plain text using rough char heuristic (matching Python). */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.length() + 3) / 4);
    }

    /** Estimate total tokens for a conversation, including 4/3 padding. */
    public static int estimateMessageTokens(List<ConversationMessage> messages) {
        int total = 0;
        int imageTokenEstimate = visionTokenBudgetPerImage();
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.content()) {
                switch (block) {
                    case ContentBlock.TextBlock tb -> total += estimateTokens(tb.text());
                    case ContentBlock.ToolResultBlock trb -> total += estimateTokens(trb.content());
                    case ContentBlock.ToolUseBlock tub -> {
                        total += estimateTokens(tub.name());
                        total += estimateTokens(tub.input() != null ? tub.input().toString() : "");
                    }
                    case ContentBlock.ImageBlock ib -> total += imageTokenEstimate;
                }
            }
        }
        return (int) (total * TOKEN_ESTIMATION_PADDING);
    }

    private static int visionTokenBudgetPerImage() {
        String raw = System.getenv().getOrDefault("OPENHARNESS_IMAGE_TOKEN_ESTIMATE", "").strip();
        if (!raw.isEmpty()) {
            try { return Math.max(64, Integer.parseInt(raw)); }
            catch (NumberFormatException e) { /* ignore */ }
        }
        return DEFAULT_VISION_IMAGE_TOKEN_ESTIMATE;
    }

    // ------------------------------------------------------------------
    // Message sanitization
    // ------------------------------------------------------------------

    /**
     * Normalize conversation history into a provider-safe sequence.
     * Matching Python's sanitize_conversation_messages().
     */
    public static List<ConversationMessage> sanitizeConversationMessages(List<ConversationMessage> messages) {
        List<ConversationMessage> sanitized = new ArrayList<>();
        Set<String> pendingToolUseIds = new HashSet<>();
        Integer pendingToolUseIndex = null;

        for (ConversationMessage message : messages) {
            if (message.role() == Role.ASSISTANT && message.isEffectivelyEmpty()) {
                continue;
            }

            List<ContentBlock.ToolUseBlock> toolUses = message.role() == Role.ASSISTANT
                    ? message.toolUses() : List.of();
            List<ContentBlock.ToolResultBlock> toolResults = message.role() == Role.USER
                    ? message.toolResults() : List.of();

            boolean matchedPendingToolResults = false;
            if (!pendingToolUseIds.isEmpty()) {
                Set<String> resultIds = toolResults.stream()
                        .map(ContentBlock.ToolResultBlock::toolUseId)
                        .collect(Collectors.toSet());
                if (message.role() != Role.USER || !resultIds.containsAll(pendingToolUseIds)) {
                    if (pendingToolUseIndex != null && pendingToolUseIndex < sanitized.size()) {
                        sanitized.remove((int) pendingToolUseIndex);
                    }
                    pendingToolUseIds = Set.of();
                    pendingToolUseIndex = null;
                } else {
                    matchedPendingToolResults = true;
                    pendingToolUseIds = Set.of();
                    pendingToolUseIndex = null;
                }
            }

            if (message.role() == Role.USER && !toolResults.isEmpty() && !matchedPendingToolResults) {
                List<ContentBlock> content = new ArrayList<>();
                for (ContentBlock block : message.content()) {
                    if (!(block instanceof ContentBlock.ToolResultBlock)) {
                        content.add(block);
                    }
                }
                if (content.isEmpty()) continue;
                sanitized.add(message.withContent(content));
                continue;
            }

            if (message.role() == Role.ASSISTANT && !toolUses.isEmpty()) {
                pendingToolUseIds = toolUses.stream()
                        .map(ContentBlock.ToolUseBlock::id)
                        .collect(Collectors.toSet());
                pendingToolUseIndex = sanitized.size();
            }

            sanitized.add(message);
        }

        // Trim trailing orphan tool_use at end
        if (!pendingToolUseIds.isEmpty() && pendingToolUseIndex != null
                && pendingToolUseIndex < sanitized.size()) {
            sanitized.remove((int) pendingToolUseIndex);
        }

        return sanitized;
    }

    // ------------------------------------------------------------------
    // Tool pair boundary preservation
    // ------------------------------------------------------------------

    /** Return True when a preserve boundary would split a tool_use/result pair. */
    static boolean boundaryCrossesToolPair(ConversationMessage previous, ConversationMessage current) {
        if (previous.role() != Role.ASSISTANT || current.role() != Role.USER) return false;
        Set<String> pendingToolIds = previous.toolUses().stream()
                .map(ContentBlock.ToolUseBlock::id)
                .collect(Collectors.toSet());
        if (pendingToolIds.isEmpty()) return false;
        Set<String> resultIds = current.toolResults().stream()
                .map(ContentBlock.ToolResultBlock::toolUseId)
                .collect(Collectors.toSet());
        return !Collections.disjoint(pendingToolIds, resultIds);
    }

    /**
     * Split older/newer segments without cutting through a tool_use/result pair.
     * Matching Python's _split_preserving_tool_pairs().
     */
    static List<List<ConversationMessage>> splitPreservingToolPairs(
            List<ConversationMessage> messages, int preserveRecent) {
        if (messages.size() <= preserveRecent) {
            return List.of(List.of(), sanitizeConversationMessages(new ArrayList<>(messages)));
        }

        int splitIndex = Math.max(0, messages.size() - preserveRecent);
        while (splitIndex > 0 && boundaryCrossesToolPair(messages.get(splitIndex - 1), messages.get(splitIndex))) {
            splitIndex--;
        }

        List<ConversationMessage> older = new ArrayList<>(messages.subList(0, splitIndex));
        List<ConversationMessage> newer = sanitizeConversationMessages(
                new ArrayList<>(messages.subList(splitIndex, messages.size())));
        return List.of(older, newer);
    }

    // ------------------------------------------------------------------
    // is_microcompactable_tool_result
    // ------------------------------------------------------------------

    /**
     * Return True when a tool result should be eligible for old-result clearing.
     * Matching Python's is_microcompactable_tool_result().
     */
    public static boolean isMicrocompactableToolResult(String toolName, String content) {
        String normalized = toolName.strip();
        if (normalized.startsWith("mcp__")) return true;
        return content != null && content.length() >= DEFAULT_MICROCOMPACT_TOOL_RESULT_CHARS;
    }

    // ------------------------------------------------------------------
    // L1: Microcompact
    // ------------------------------------------------------------------

    /** Walk messages and collect tool_use IDs whose results are compactable. */
    static List<String> collectCompactableToolIds(List<ConversationMessage> messages) {
        List<String> orderedIds = new ArrayList<>();
        Map<String, String> toolNames = new HashMap<>();
        Map<String, String> resultContent = new HashMap<>();

        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.ToolUseBlock tub) {
                    orderedIds.add(tub.id());
                    toolNames.put(tub.id(), tub.name());
                } else if (block instanceof ContentBlock.ToolResultBlock trb) {
                    resultContent.put(trb.toolUseId(), trb.content());
                }
            }
        }

        List<String> compactable = new ArrayList<>();
        for (String toolId : orderedIds) {
            String name = toolNames.getOrDefault(toolId, "");
            if (COMPACTABLE_TOOLS.contains(name)
                    || isMicrocompactableToolResult(name, resultContent.getOrDefault(toolId, ""))) {
                compactable.add(toolId);
            }
        }
        return compactable;
    }

    /**
     * Clear old compactable tool results, keeping the most recent keep_recent.
     * Matching Python's microcompact_messages().
     *
     * @return tokens saved
     */
    public int microcompactMessages(List<ConversationMessage> messages, int keepRecent) {
        keepRecent = Math.max(1, keepRecent);
        List<String> allIds = collectCompactableToolIds(messages);

        if (allIds.size() <= keepRecent) return 0;

        Set<String> keepSet = new HashSet<>(allIds.subList(allIds.size() - keepRecent, allIds.size()));
        Set<String> clearSet = new HashSet<>(allIds);
        clearSet.removeAll(keepSet);

        int tokensSaved = 0;
        for (ConversationMessage msg : messages) {
            if (msg.role() != Role.USER) continue;
            List<ContentBlock> newContent = new ArrayList<>();
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.ToolResultBlock trb
                        && clearSet.contains(trb.toolUseId())
                        && !TIME_BASED_MC_CLEARED_MESSAGE.equals(trb.content())) {
                    tokensSaved += estimateTokens(trb.content());
                    newContent.add(new ContentBlock.ToolResultBlock(
                            trb.toolUseId(), TIME_BASED_MC_CLEARED_MESSAGE, trb.isError()));
                } else {
                    newContent.add(block);
                }
            }
            // Mutate in place (like Python)
            List<ContentBlock> mutableContent = new ArrayList<>(msg.content());
            mutableContent.clear();
            mutableContent.addAll(newContent);
        }

        if (tokensSaved > 0) {
            LOG.info("Microcompact cleared " + clearSet.size() + " tool results, saved ~" + tokensSaved + " tokens");
        }
        return tokensSaved;
    }

    public int microcompactMessages(List<ConversationMessage> messages) {
        return microcompactMessages(messages, DEFAULT_KEEP_RECENT);
    }

    // ------------------------------------------------------------------
    // L1 helpers: build new message list after microcompact (non-mutating)
    // ------------------------------------------------------------------

    /** Create a new message list with micro-compacted tool results (non-mutating). */
    public static List<ConversationMessage> applyMicrocompact(List<ConversationMessage> messages, int keepRecent) {
        keepRecent = Math.max(1, keepRecent);
        List<String> allIds = collectCompactableToolIds(messages);
        if (allIds.size() <= keepRecent) return messages;

        Set<String> keepSet = new HashSet<>(allIds.subList(allIds.size() - keepRecent, allIds.size()));
        Set<String> clearSet = new HashSet<>(allIds);
        clearSet.removeAll(keepSet);

        List<ConversationMessage> result = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            List<ContentBlock> newContent = new ArrayList<>();
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.ToolResultBlock trb
                        && clearSet.contains(trb.toolUseId())
                        && !TIME_BASED_MC_CLEARED_MESSAGE.equals(trb.content())) {
                    newContent.add(new ContentBlock.ToolResultBlock(
                            trb.toolUseId(), TIME_BASED_MC_CLEARED_MESSAGE, trb.isError()));
                } else {
                    newContent.add(block);
                }
            }
            result.add(new ConversationMessage(msg.role(), newContent));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Context collapse
    // ------------------------------------------------------------------

    static String collapseText(String text) {
        if (text == null || text.length() <= CONTEXT_COLLAPSE_TEXT_CHAR_LIMIT) return text;
        int omitted = text.length() - CONTEXT_COLLAPSE_HEAD_CHARS - CONTEXT_COLLAPSE_TAIL_CHARS;
        String head = text.substring(0, CONTEXT_COLLAPSE_HEAD_CHARS).stripTrailing();
        String tail = text.substring(text.length() - CONTEXT_COLLAPSE_TAIL_CHARS).stripLeading();
        return head + "\n...[collapsed " + omitted + " chars]...\n" + tail;
    }

    /**
     * Deterministically shrink oversized text blocks before full compact.
     * Returns null if no change was beneficial.
     */
    public static List<ConversationMessage> tryContextCollapse(
            List<ConversationMessage> messages, int preserveRecent) {
        if (messages.size() <= preserveRecent + 2) return null;

        List<List<ConversationMessage>> split = splitPreservingToolPairs(messages, preserveRecent);
        List<ConversationMessage> older = split.get(0);
        List<ConversationMessage> newer = split.get(1);

        boolean changed = false;
        List<ConversationMessage> collapsedOlder = new ArrayList<>();
        for (ConversationMessage message : older) {
            List<ContentBlock> newBlocks = new ArrayList<>();
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.TextBlock tb) {
                    String collapsed = collapseText(tb.text());
                    if (!collapsed.equals(tb.text())) changed = true;
                    newBlocks.add(new ContentBlock.TextBlock(collapsed));
                } else if (block instanceof ContentBlock.ToolResultBlock trb) {
                    String collapsed = collapseText(trb.content());
                    if (!collapsed.equals(trb.content())) changed = true;
                    newBlocks.add(new ContentBlock.ToolResultBlock(
                            trb.toolUseId(), collapsed, trb.isError()));
                } else {
                    newBlocks.add(block);
                }
            }
            collapsedOlder.add(new ConversationMessage(message.role(), newBlocks));
        }

        if (!changed) return null;

        List<ConversationMessage> result = new ArrayList<>(collapsedOlder);
        result.addAll(newer);
        if (estimateMessageTokens(result) >= estimateMessageTokens(messages)) return null;
        return result;
    }

    // ------------------------------------------------------------------
    // Group messages by prompt round
    // ------------------------------------------------------------------

    static List<List<ConversationMessage>> groupMessagesByPromptRound(List<ConversationMessage> messages) {
        List<List<ConversationMessage>> groups = new ArrayList<>();
        List<ConversationMessage> current = new ArrayList<>();
        for (ConversationMessage message : messages) {
            boolean hasToolResults = message.toolResults().stream().findAny().isPresent();
            boolean startsNewRound = message.role() == Role.USER
                    && !hasToolResults
                    && !message.text().isBlank();
            if (startsNewRound && !current.isEmpty()) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(message);
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    /** Drop the oldest prompt rounds when the compact request itself is too large. */
    static List<ConversationMessage> truncateHeadForPtlRetry(List<ConversationMessage> messages) {
        List<List<ConversationMessage>> groups = groupMessagesByPromptRound(messages);
        if (groups.size() < 2) return null;

        int dropCount = Math.max(1, groups.size() / 5);
        dropCount = Math.min(dropCount, groups.size() - 1);

        List<ConversationMessage> retained = new ArrayList<>();
        for (int i = dropCount; i < groups.size(); i++) {
            retained.addAll(groups.get(i));
        }
        if (retained.isEmpty()) return null;
        if (!retained.isEmpty() && retained.get(0).role() == Role.ASSISTANT) {
            List<ConversationMessage> result = new ArrayList<>();
            result.add(ConversationMessage.fromUserText(PTL_RETRY_MARKER));
            result.addAll(retained);
            return result;
        }
        return retained;
    }

    /** Check if an exception is a "prompt too long" error. */
    static boolean isPromptTooLongError(Throwable exc) {
        String text = exc.getMessage() != null ? exc.getMessage().toLowerCase() : "";
        String[] needles = {
                "prompt too long", "context_length_exceeded", "context length",
                "maximum context", "context window", "input tokens exceed",
                "messages resulted in", "reduce the length of the messages",
                "configured limit", "too many tokens", "too large for the model",
                "maximum context length", "exceed_context",
                "exceeds the available context size", "available context size"
        };
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Attachment path and tool extraction
    // ------------------------------------------------------------------

    static List<String> extractAttachmentPaths(List<ConversationMessage> messages) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Pattern pathPattern = Pattern.compile("path:\\s*([^)\\n]+)");
        Pattern attachmentPattern = Pattern.compile("\\[attachment:\\s*([^\\]]+)\\]");

        for (ConversationMessage message : messages) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.ImageBlock ib && ib.sourcePath() != null && !ib.sourcePath().isEmpty()) {
                    String path = ib.sourcePath().strip();
                    if (!path.isEmpty() && seen.add(path)) found.add(path);
                } else if (block instanceof ContentBlock.TextBlock tb) {
                    Matcher pm = pathPattern.matcher(tb.text());
                    while (pm.find() && found.size() < MAX_COMPACT_ATTACHMENTS) {
                        String path = pm.group(1).strip();
                        if (!path.isEmpty() && seen.add(path)) found.add(path);
                    }
                    Matcher am = attachmentPattern.matcher(tb.text());
                    while (am.find() && found.size() < MAX_COMPACT_ATTACHMENTS) {
                        String path = am.group(1).strip();
                        if (!path.isEmpty() && !path.contains("download failed") && seen.add(path)) {
                            found.add(path);
                        }
                    }
                }
                if (found.size() >= MAX_COMPACT_ATTACHMENTS) return found;
            }
        }
        return found;
    }

    static List<String> extractDiscoveredTools(List<ConversationMessage> messages) {
        List<String> discovered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ConversationMessage message : messages) {
            for (ContentBlock.ToolUseBlock tub : message.toolUses()) {
                if (tub.name() != null && seen.add(tub.name())) {
                    discovered.add(tub.name());
                }
                if (discovered.size() >= MAX_DISCOVERED_TOOLS) return discovered;
            }
        }
        return discovered;
    }

    // ------------------------------------------------------------------
    // Compact attachment builders (8 types)
    // ------------------------------------------------------------------

    static CompactAttachment createAttachment(String kind, String title, List<String> lines,
                                              Map<String, Object> metadata) {
        List<String> filtered = lines.stream()
                .filter(l -> l != null && !l.isBlank())
                .toList();
        if (filtered.isEmpty()) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) sanitizeMetadata(metadata);
        return new CompactAttachment(kind, title, String.join("\n", filtered),
                sanitized != null ? sanitized : Map.of());
    }

    static CompactAttachment createRecentAttachmentsAttachment(List<String> attachmentPaths) {
        if (attachmentPaths == null || attachmentPaths.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        lines.add("Keep these local attachment paths in working memory:");
        attachmentPaths.forEach(p -> lines.add("- " + p));
        return createAttachment("recent_attachments", "Recent local attachments", lines,
                Map.of("paths", attachmentPaths));
    }

    static CompactAttachment createRecentFilesAttachment(List<Map<String, Object>> readFileState) {
        if (readFileState == null || readFileState.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        lines.add("Recently read files that may still matter:");
        List<Map<String, Object>> entries = new ArrayList<>();
        readFileState.stream()
                .filter(e -> e.get("path") instanceof String s && !s.isBlank())
                .sorted(Comparator.comparingDouble(e -> -((Number) e.getOrDefault("timestamp", 0.0)).doubleValue()))
                .limit(4)
                .forEach(entry -> {
                    String path = ((String) entry.get("path")).strip();
                    String span = entry.get("span") instanceof String s ? s.strip() : "";
                    String preview = entry.get("preview") instanceof String s ? s.strip() : "";
                    StringBuilder bullet = new StringBuilder("- " + path);
                    if (!span.isEmpty()) bullet.append(" (").append(span).append(")");
                    lines.add(bullet.toString());
                    if (!preview.isEmpty()) lines.add("  Preview: " + preview);
                    entries.add(Map.of("path", path, "span", span, "preview", preview,
                            "timestamp", entry.getOrDefault("timestamp", 0.0)));
                });
        return createAttachment("recent_files", "Recently read files", lines,
                Map.of("entries", entries));
    }

    static CompactAttachment createTaskFocusAttachment(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object state = metadata.get("task_focus_state");
        if (!(state instanceof Map<?, ?> s)) return null;
        String goal = s.get("goal") instanceof String g ? g.strip() : "";
        List<String> recentGoals = stringList(s.get("recent_goals"));
        List<String> activeArtifacts = stringList(s.get("active_artifacts"));
        List<String> verifiedState = stringList(s.get("verified_state"));
        String nextStep = s.get("next_step") instanceof String ns ? ns.strip() : "";
        if (goal.isEmpty() && recentGoals.isEmpty() && activeArtifacts.isEmpty()
                && verifiedState.isEmpty() && nextStep.isEmpty()) return null;

        List<String> lines = new ArrayList<>();
        lines.add("Current working focus to preserve across compaction:");
        if (!goal.isEmpty()) lines.add("- Goal: " + goal);
        if (!recentGoals.isEmpty()) {
            lines.add("- Recent user goals that still matter:");
            recentGoals.subList(0, Math.min(recentGoals.size(), 3)).forEach(g2 -> lines.add("  - " + g2));
        }
        if (!activeArtifacts.isEmpty()) {
            lines.add("- Active artifacts in play:");
            activeArtifacts.stream().limit(5).forEach(a -> lines.add("  - " + a));
        }
        if (!verifiedState.isEmpty()) {
            lines.add("- Verified state already established:");
            verifiedState.stream().limit(4).forEach(v -> lines.add("  - " + v));
        }
        if (!nextStep.isEmpty()) lines.add("- Suggested next step: " + nextStep);
        return createAttachment("task_focus", "Current working focus", lines, Map.of(
                "goal", goal, "recent_goals", recentGoals, "active_artifacts", activeArtifacts,
                "verified_state", verifiedState, "next_step", nextStep));
    }

    static CompactAttachment createRecentVerifiedWorkAttachment(Object verifiedWork) {
        if (!(verifiedWork instanceof List<?> list) || list.isEmpty()) return null;
        List<String> entries = list.stream()
                .map(Object::toString).map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        if (entries.isEmpty()) return null;
        entries = entries.subList(Math.max(0, entries.size() - 8), entries.size());
        List<String> lines = new ArrayList<>();
        lines.add("These steps or conclusions were explicitly verified before compaction:");
        entries.forEach(e -> lines.add("- " + e));
        return createAttachment("recent_verified_work", "Recently verified work", lines,
                Map.of("entries", entries));
    }

    static CompactAttachment createPlanAttachment(Map<String, Object> metadata) {
        if (metadata == null) return null;
        String permissionMode = Objects.toString(metadata.get("permission_mode"), "").strip().toLowerCase();
        if (!"plan".equals(permissionMode)) return null;
        List<String> lines = new ArrayList<>();
        lines.add("Plan mode is still active for this session.");
        lines.add("Do not execute mutating tools until the user explicitly exits plan mode.");
        String planSummary = Objects.toString(metadata.get("plan_summary"), "").strip();
        if (!planSummary.isEmpty()) lines.add("Current plan summary: " + planSummary);
        return createAttachment("plan", "Plan mode context", lines,
                Map.of("permission_mode", permissionMode, "plan_summary", planSummary));
    }

    static CompactAttachment createInvokedSkillsAttachment(Object invokedSkills) {
        if (!(invokedSkills instanceof List<?> list) || list.isEmpty()) return null;
        List<String> normalized = list.stream()
                .map(Object::toString).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        if (normalized.isEmpty()) return null;
        normalized = normalized.subList(Math.max(0, normalized.size() - 8), normalized.size());
        return createAttachment("invoked_skills", "Skills used earlier in the session",
                List.of("The following skills were invoked and may still shape the next step:",
                        "- " + String.join(", ", normalized)),
                Map.of("skills", normalized));
    }

    static CompactAttachment createAsyncAgentAttachment(Object asyncAgentState) {
        if (!(asyncAgentState instanceof List<?> list) || list.isEmpty()) return null;
        List<String> entries = list.stream()
                .map(Object::toString).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        if (entries.isEmpty()) return null;
        entries = entries.subList(Math.max(0, entries.size() - 6), entries.size());
        List<String> lines = new ArrayList<>();
        lines.add("Recent async-agent/background-task activity:");
        entries.forEach(e -> lines.add("- " + e));
        return createAttachment("async_agents", "Async agent and background task state", lines,
                Map.of("entries", entries));
    }

    static CompactAttachment createWorkLogAttachment(Object recentWorkLog) {
        if (!(recentWorkLog instanceof List<?> list) || list.isEmpty()) return null;
        List<String> entries = list.stream()
                .map(Object::toString).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        if (entries.isEmpty()) return null;
        entries = entries.subList(Math.max(0, entries.size() - 8), entries.size());
        List<String> lines = new ArrayList<>();
        lines.add("Recent work and verification steps taken in this session:");
        entries.forEach(e -> lines.add("- " + e));
        return createAttachment("recent_work_log", "Recent execution checkpoints", lines,
                Map.of("entries", entries));
    }

    static List<CompactAttachment> buildCompactAttachments(List<ConversationMessage> messages,
                                                           Map<String, Object> metadata) {
        if (metadata == null) metadata = Map.of();
        List<CompactAttachment> attachments = new ArrayList<>();
        List<String> attachmentPaths = extractAttachmentPaths(messages);

        addIfNotNull(attachments, createTaskFocusAttachment(metadata));
        addIfNotNull(attachments, createRecentVerifiedWorkAttachment(metadata.get("recent_verified_work")));
        addIfNotNull(attachments, createRecentAttachmentsAttachment(attachmentPaths));
        addIfNotNull(attachments, createRecentFilesAttachment(
                metadata.get("read_file_state") instanceof List<?> l
                        ? l.stream().filter(Map.class::isInstance).map(e -> (Map<String, Object>) e).toList()
                        : null));
        addIfNotNull(attachments, createPlanAttachment(metadata));
        addIfNotNull(attachments, createInvokedSkillsAttachment(metadata.get("invoked_skills")));
        addIfNotNull(attachments, createAsyncAgentAttachment(metadata.get("async_agent_state")));
        addIfNotNull(attachments, createWorkLogAttachment(metadata.get("recent_work_log")));

        return attachments;
    }

    private static void addIfNotNull(List<CompactAttachment> list, CompactAttachment item) {
        if (item != null) list.add(item);
    }

    // ------------------------------------------------------------------
    // Boundary marker and post-compact rebuild
    // ------------------------------------------------------------------

    public static ConversationMessage renderCompactAttachment(CompactAttachment attachment) {
        String header = "[Compact attachment: " + attachment.kind() + "] " + attachment.title();
        String text = (header + "\n" + attachment.body()).strip();
        return ConversationMessage.fromUserText(text);
    }

    public static ConversationMessage createCompactBoundaryMessage(Map<String, Object> metadata) {
        List<String> lines = new ArrayList<>();
        lines.add("[Compact boundary marker]");
        lines.add("Earlier conversation was compacted. Use the summary and preserved assets below as the continuity boundary.");

        String trigger = Objects.toString(metadata.getOrDefault("trigger", ""), "").strip();
        String compactKind = Objects.toString(metadata.getOrDefault("compact_kind", ""), "").strip();
        Object preMessages = metadata.get("pre_compact_message_count");
        Object preTokens = metadata.get("pre_compact_token_count");
        Object postMessages = metadata.get("post_compact_message_count");
        Object postTokens = metadata.get("post_compact_token_count");

        if (!trigger.isEmpty()) lines.add("Trigger: " + trigger);
        if (!compactKind.isEmpty()) lines.add("Compaction kind: " + compactKind);
        if (preMessages != null || preTokens != null) {
            lines.add("Pre-compact footprint: messages="
                    + (preMessages != null ? preMessages : "unknown") + ", tokens="
                    + (preTokens != null ? preTokens : "unknown"));
        }
        if (postMessages != null || postTokens != null) {
            lines.add("Post-compact footprint: messages="
                    + (postMessages != null ? postMessages : "unknown") + ", tokens="
                    + (postTokens != null ? postTokens : "unknown"));
        }
        String anchor = Objects.toString(metadata.getOrDefault("preserved_segment_anchor", ""), "").strip();
        if (!anchor.isEmpty()) lines.add("Preserved segment anchor: " + anchor);

        return ConversationMessage.fromUserText(String.join("\n", lines));
    }

    public static List<ConversationMessage> buildPostCompactMessages(CompactionResult result) {
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(result.boundaryMarker());
        messages.addAll(result.summaryMessages());
        messages.addAll(result.messagesToKeep());
        result.attachments().forEach(a -> messages.add(renderCompactAttachment(a)));
        result.hookResults().forEach(a -> messages.add(renderCompactAttachment(a)));
        return messages;
    }

    // ------------------------------------------------------------------
    // Post-compact sanitization
    // ------------------------------------------------------------------

    static void sanitizeCompactionSegments(CompactionResult result) {
        if (result.summaryMessages().isEmpty() && result.messagesToKeep().isEmpty()) return;
        List<ConversationMessage> combined = new ArrayList<>();
        combined.addAll(result.summaryMessages());
        combined.addAll(result.messagesToKeep());
        List<ConversationMessage> sanitized = sanitizeConversationMessages(combined);
        int summaryCount = result.summaryMessages().size();
        result.setSummaryMessages(sanitized.subList(0, Math.min(summaryCount, sanitized.size())));
        result.setMessagesToKeep(sanitized.subList(Math.min(summaryCount, sanitized.size()), sanitized.size()));
    }

    static CompactionResult finalizeCompactionResult(CompactionResult result) {
        sanitizeCompactionSegments(result);
        List<ConversationMessage> messages = buildPostCompactMessages(result);
        result.compactMetadata().putIfAbsent("post_compact_message_count", messages.size());
        result.compactMetadata().putIfAbsent("post_compact_token_count", estimateMessageTokens(messages));
        result.setBoundaryMarker(createCompactBoundaryMessage(result.compactMetadata()));
        return result;
    }

    // ------------------------------------------------------------------
    // Hook helpers
    // ------------------------------------------------------------------

    static List<CompactAttachment> createHookAttachments(String hookNote) {
        if (hookNote == null || hookNote.isBlank()) return List.of();
        CompactAttachment attachment = createAttachment("hook_results", "Compact hook notes",
                List.of(hookNote.strip()), Map.of("note", hookNote.strip()));
        return attachment != null ? List.of(attachment) : List.of();
    }

    // ------------------------------------------------------------------
    // Metadata helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Object sanitizeMetadata(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value == null) {
            return value;
        }
        if (value instanceof java.nio.file.Path p) {
            return p.toString();
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), sanitizeMetadata(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(CompactionService::sanitizeMetadata).toList();
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    static Object sanitizeMetadataValue(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value == null) {
            return value;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), sanitizeMetadataValue(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(CompactionService::sanitizeMetadataValue).toList();
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    static boolean metadataHasCheckpoint(Map<String, Object> metadata, String checkpoint) {
        if (metadata == null) return false;
        Object checkpoints = metadata.get("compact_checkpoints");
        if (!(checkpoints instanceof List<?> list)) return false;
        return list.stream().anyMatch(entry ->
                entry instanceof Map<?, ?> m && checkpoint.equals(m.get("checkpoint")));
    }

    static Map<String, Object> recordCompactCheckpoint(Map<String, Object> carryoverMetadata,
                                                       String checkpoint, String trigger,
                                                       int messageCount, int tokenCount) {
        return recordCompactCheckpoint(carryoverMetadata, checkpoint, trigger,
                messageCount, tokenCount, null, null);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> recordCompactCheckpoint(Map<String, Object> carryoverMetadata,
                                                       String checkpoint, String trigger,
                                                       int messageCount, int tokenCount,
                                                       Integer attempt, Map<String, Object> details) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("checkpoint", checkpoint);
        payload.put("trigger", trigger);
        payload.put("message_count", messageCount);
        payload.put("token_count", tokenCount);
        if (attempt != null) payload.put("attempt", attempt);
        if (details != null) payload.putAll((Map<String, Object>) sanitizeMetadataValue(details));

        if (carryoverMetadata != null) {
            List<Map<String, Object>> checkpoints =
                    (List<Map<String, Object>>) carryoverMetadata.computeIfAbsent(
                            "compact_checkpoints", k -> new ArrayList<>());
            checkpoints.add(payload);
            carryoverMetadata.put("compact_last", payload);
        }
        return payload;
    }

    // ------------------------------------------------------------------
    // Progress callback interface
    // ------------------------------------------------------------------

    @FunctionalInterface
    public interface CompactProgressCallback {
        void onProgress(String phase, String trigger, String message,
                        Integer attempt, String checkpoint, Map<String, Object> metadata);
    }

    static void emitProgress(CompactProgressCallback callback, String phase, String trigger,
                             String message, Integer attempt, String checkpoint,
                             Map<String, Object> metadata) {
        if (callback == null) return;
        callback.onProgress(phase, trigger, message, attempt, checkpoint,
                metadata != null ? new HashMap<>(metadata) : null);
    }

    // ------------------------------------------------------------------
    // L2: Session memory compaction
    // ------------------------------------------------------------------

    static String summarizeMessageForMemory(ConversationMessage message) {
        String text = String.join(" ", message.text().split("\\s+"));
        if (!text.isEmpty()) {
            text = text.length() > 160 ? text.substring(0, 160) : text;
            return message.role().name().toLowerCase() + ": " + text;
        }
        List<ContentBlock.ToolUseBlock> toolUses = message.toolUses();
        if (!toolUses.isEmpty()) {
            return message.role().name().toLowerCase() + ": tool calls -> "
                    + toolUses.stream().limit(4).map(ContentBlock.ToolUseBlock::name)
                    .collect(Collectors.joining(", "));
        }
        if (message.toolResults().stream().findAny().isPresent()) {
            return message.role().name().toLowerCase() + ": tool results returned";
        }
        return message.role().name().toLowerCase() + ": [non-text content]";
    }

    /**
     * Build a compaction message from the persisted session-memory file.
     * Matching Python's _build_file_session_memory_message().
     */
    static ConversationMessage buildFileSessionMemoryMessage(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object path = metadata.get("session_memory_path");
        if (!(path instanceof String s) || s.isEmpty()) return null;
        try {
            String content = SessionMemoryService.getSessionMemoryContent(s);
            String text = SessionMemoryService.sessionMemoryToCompactText(content);
            if (text.isEmpty()) return null;
            return ConversationMessage.fromUserText(text);
        } catch (Exception e) {
            return null;
        }
    }

    static ConversationMessage buildSessionMemoryMessage(List<ConversationMessage> messages) {
        List<String> lines = new ArrayList<>();
        int totalChars = 0;
        for (ConversationMessage message : messages) {
            String line = summarizeMessageForMemory(message);
            if (line.isEmpty()) continue;
            int projected = totalChars + line.length() + 1;
            if (!lines.isEmpty() && (lines.size() >= SESSION_MEMORY_MAX_LINES
                    || projected >= SESSION_MEMORY_MAX_CHARS)) {
                lines.add("... earlier context condensed ...");
                break;
            }
            lines.add(line);
            totalChars = projected;
        }
        if (lines.isEmpty()) return null;
        return ConversationMessage.fromUserText(
                "Session memory summary from earlier in this conversation:\n" + String.join("\n", lines));
    }

    /**
     * Cheap deterministic compaction for long chats before full LLM compaction.
     * Matching Python's try_session_memory_compaction().
     */
    public static CompactionResult trySessionMemoryCompaction(
            List<ConversationMessage> messages, int preserveRecent,
            String trigger, Map<String, Object> metadata) {
        if (messages.size() <= preserveRecent + 4) return null;

        List<List<ConversationMessage>> split = splitPreservingToolPairs(messages, preserveRecent);
        List<ConversationMessage> older = split.get(0);
        List<ConversationMessage> newer = split.get(1);

        ConversationMessage fileSummaryMessage = buildFileSessionMemoryMessage(metadata);
        ConversationMessage summaryMessage = fileSummaryMessage != null
                ? fileSummaryMessage : buildSessionMemoryMessage(older);
        if (summaryMessage == null) return null;

        List<ConversationMessage> provisional = new ArrayList<>();
        provisional.add(summaryMessage);
        provisional.addAll(newer);
        if (estimateMessageTokens(provisional) >= estimateMessageTokens(messages)
                && provisional.size() >= messages.size()) {
            return null;
        }

        Map<String, Object> compactMetadata = new HashMap<>();
        compactMetadata.put("trigger", trigger);
        compactMetadata.put("compact_kind", "session_memory");
        compactMetadata.put("pre_compact_message_count", messages.size());
        compactMetadata.put("pre_compact_token_count", estimateMessageTokens(messages));
        compactMetadata.put("preserve_recent", preserveRecent);
        compactMetadata.put("used_session_memory", true);
        compactMetadata.put("used_file_session_memory", fileSummaryMessage != null);
        compactMetadata.put("pre_compact_discovered_tools", extractDiscoveredTools(older));
        compactMetadata.put("attachments", extractAttachmentPaths(older));

        CompactionResult result = new CompactionResult(
                trigger, "session_memory",
                createCompactBoundaryMessage(compactMetadata),
                List.of(summaryMessage),
                new ArrayList<>(newer),
                buildCompactAttachments(older, metadata),
                List.of(),
                compactMetadata);
        return finalizeCompactionResult(result);
    }

    // ------------------------------------------------------------------
    // L3: Full LLM compact
    // ------------------------------------------------------------------

    static final String NO_TOOLS_PREAMBLE = """
            CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.

            - Do NOT use read_file, bash, grep, glob, edit_file, write_file, or ANY other tool.
            - You already have all the context you need in the conversation above.
            - Tool calls will be REJECTED and will waste your only turn — you will fail the task.
            - Your entire response must be plain text: an <analysis> block followed by a <summary> block.

            """;

    static final String BASE_COMPACT_PROMPT = """
            Your task is to create a detailed summary of the conversation so far. This summary will replace the earlier messages, so it must capture all important information.

            First, draft your analysis inside <analysis> tags. Walk through the conversation chronologically and extract:
            - Every user request and intent (explicit and implicit)
            - The approach taken and technical decisions made
            - Specific code, files, and configurations discussed (with paths and line numbers where available)
            - All errors encountered and how they were fixed
            - Any user feedback or corrections

            Then, produce a structured summary inside <summary> tags with these sections:

            1. **Primary Request and Intent**: All user requests in full detail, including nuances and constraints.
            2. **Key Technical Concepts**: Technologies, frameworks, patterns, and conventions discussed.
            3. **Files and Code Sections**: Every file examined or modified, with specific code snippets and line numbers.
            4. **Errors and Fixes**: Every error encountered, its cause, and how it was resolved.
            5. **Problem Solving**: Problems solved and approaches that worked vs. didn't work.
            6. **All User Messages**: Non-tool-result user messages (preserve exact wording for context).
            7. **Pending Tasks**: Explicitly requested work that hasn't been completed yet.
            8. **Current Work**: Detailed description of the last task being worked on before compaction.
            9. **Optional Next Step**: The single most logical next step, directly aligned with the user's recent request.
            """;

    static final String NO_TOOLS_TRAILER = """

            REMINDER: Do NOT call any tools. Respond with plain text only — an <analysis> block followed by a <summary> block. Tool calls will be rejected and you will fail the task.""";

    public static String getCompactPrompt(String customInstructions) {
        StringBuilder sb = new StringBuilder();
        sb.append(NO_TOOLS_PREAMBLE);
        sb.append(BASE_COMPACT_PROMPT);
        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("\n\nAdditional Instructions:\n").append(customInstructions);
        }
        sb.append(NO_TOOLS_TRAILER);
        return sb.toString();
    }

    /** Strip the <analysis> scratchpad and extract <summary> content. */
    public static String formatCompactSummary(String rawSummary) {
        String text = rawSummary.replaceAll("<analysis>[\\s\\S]*?</analysis>", "");
        Matcher m = Pattern.compile("<summary>([\\s\\S]*?)</summary>").matcher(text);
        if (m.find()) {
            text = text.replace(m.group(0), "Summary:\n" + m.group(1).strip());
        }
        text = text.replaceAll("\n\n+", "\n\n");
        return text.strip();
    }

    public static String buildCompactSummaryMessage(String summary, boolean suppressFollowUp,
                                                    boolean recentPreserved) {
        String formatted = formatCompactSummary(summary);
        StringBuilder text = new StringBuilder();
        text.append("This session is being continued from a previous conversation that ran ");
        text.append("out of context. The summary below covers the earlier portion of the ");
        text.append("conversation.\n\n");
        text.append(formatted);
        if (recentPreserved) {
            text.append("\n\nRecent messages are preserved verbatim.");
        }
        if (suppressFollowUp) {
            text.append("\nContinue the conversation from where it left off without asking ");
            text.append("the user any further questions. Resume directly — do not acknowledge ");
            text.append("the summary, do not recap what was happening, do not preface with ");
            text.append("\"I'll continue\" or similar. Pick up the last task as if the break ");
            text.append("never happened.");
        }
        return text.toString();
    }

    /** Replace image blocks with text placeholders for the summarizer call. */
    static List<ConversationMessage> replaceImagesWithPlaceholders(List<ConversationMessage> messages) {
        List<ConversationMessage> replaced = new ArrayList<>();
        for (ConversationMessage message : messages) {
            List<ContentBlock> nextContent = new ArrayList<>();
            boolean changed = false;
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.ImageBlock ib) {
                    changed = true;
                    String label = ib.sourcePath() != null && !ib.sourcePath().isEmpty()
                            ? ib.sourcePath().strip() : "inline";
                    nextContent.add(new ContentBlock.TextBlock(
                            "[Image omitted from compaction summarization; source: " + label + ".]\n"));
                } else {
                    nextContent.add(block);
                }
            }
            replaced.add(changed ? new ConversationMessage(message.role(), nextContent) : message);
        }
        return replaced;
    }

    /**
     * Full LLM compaction — structured summarization via API.
     * Matching Python's compact_conversation().
     */
    public CompactionResult compactConversation(
            List<ConversationMessage> messages,
            StreamingApiClient apiClient,
            String model,
            String systemPrompt,
            int preserveRecent,
            String customInstructions,
            boolean suppressFollowUp,
            String trigger,
            CompactProgressCallback progressCallback,
            HookExecutor hookExecutor,
            Map<String, Object> carryoverMetadata) {

        if (messages.size() <= preserveRecent) {
            return buildPassthroughResult(messages, trigger, "full",
                    Map.of("reason", "conversation already within preserve_recent window"));
        }

        // Step 1: microcompact to reduce tokens cheaply
        int tokensFreed = microcompactMessages(messages, DEFAULT_KEEP_RECENT);
        int preCompactTokens = estimateMessageTokens(messages);
        LOG.info("Compacting conversation: " + messages.size() + " messages, ~" + preCompactTokens + " tokens");

        // Step 2: split into older (summarize) and newer (preserve)
        List<List<ConversationMessage>> split = splitPreservingToolPairs(messages, preserveRecent);
        List<ConversationMessage> older = split.get(0);
        List<ConversationMessage> newer = split.get(1);

        // Step 3: build compact request
        String compactPrompt = getCompactPrompt(customInstructions);
        List<ConversationMessage> compactMessages = new ArrayList<>(older);
        compactMessages.add(ConversationMessage.fromUserText(compactPrompt));

        List<String> attachmentPaths = extractAttachmentPaths(older);
        List<String> discoveredTools = extractDiscoveredTools(older);

        Map<String, Object> hookPayload = new HashMap<>();
        hookPayload.put("event", HookEvent.PRE_COMPACT.name());
        hookPayload.put("trigger", trigger);
        hookPayload.put("model", model);
        hookPayload.put("message_count", messages.size());
        hookPayload.put("token_count", preCompactTokens);
        hookPayload.put("preserve_recent", preserveRecent);
        hookPayload.put("attachments", attachmentPaths);
        hookPayload.put("discovered_tools", discoveredTools);
        if (carryoverMetadata != null) hookPayload.putAll(carryoverMetadata);

        recordCompactCheckpoint(carryoverMetadata, "compact_prepare", trigger,
                messages.size(), preCompactTokens);

        emitProgress(progressCallback, "hooks_start", trigger,
                "Preparing conversation compaction.", null, "compact_hooks_start", null);

        if (hookExecutor != null) {
            var hookResult = hookExecutor.execute(HookEvent.PRE_COMPACT, hookPayload);
            if (hookResult.blocked()) {
                String reason = hookResult.reason() != null ? hookResult.reason()
                        : "pre-compact hook blocked compaction";
                recordCompactCheckpoint(carryoverMetadata, "compact_failed", trigger,
                        messages.size(), preCompactTokens);
                emitProgress(progressCallback, "compact_failed", trigger, reason,
                        null, "compact_failed", null);
                return buildPassthroughResult(messages, trigger, "full", Map.of("reason", reason));
            }
        }

        emitProgress(progressCallback, "compact_start", trigger,
                "Compacting conversation memory.", null, "compact_start", null);

        // Step 4: call the LLM
        String summaryText = "";
        List<ConversationMessage> retryMessages = compactMessages;
        int ptlRetries = 0;
        int streamingRetries = 0;
        int attempt = 0;

        for (attempt = 1; attempt <= MAX_COMPACT_STREAMING_RETRIES + 1; attempt++) {
            try {
                List<ConversationMessage> requestMessages = replaceImagesWithPlaceholders(retryMessages);
                summaryText = collectStreamingResponse(apiClient, model,
                        systemPrompt != null && !systemPrompt.isBlank()
                                ? systemPrompt : "You are a conversation summarizer.",
                        requestMessages);
                break;
            } catch (Exception exc) {
                if (isPromptTooLongError(exc) && ptlRetries < MAX_PTL_RETRIES) {
                    List<ConversationMessage> headMessages = retryMessages.subList(0, retryMessages.size() - 1);
                    List<ConversationMessage> truncated = truncateHeadForPtlRetry(headMessages);
                    if (truncated != null) {
                        ptlRetries++;
                        retryMessages = new ArrayList<>(truncated);
                        retryMessages.add(retryMessages.get(retryMessages.size() - 1)); // keep the prompt
                        emitProgress(progressCallback, "compact_retry", trigger,
                                "Compaction prompt was too large; retrying with older context trimmed.",
                                ptlRetries, "compact_retry_prompt_too_long",
                                recordCompactCheckpoint(carryoverMetadata, "compact_retry_prompt_too_long",
                                        trigger, retryMessages.size(), estimateMessageTokens(retryMessages),
                                        ptlRetries, Map.of("ptl_retries", ptlRetries)));
                        continue;
                    }
                }
                streamingRetries++;
                if (attempt > MAX_COMPACT_STREAMING_RETRIES) {
                    emitProgress(progressCallback, "compact_failed", trigger,
                            exc.getMessage(), attempt, "compact_failed",
                            recordCompactCheckpoint(carryoverMetadata, "compact_failed", trigger,
                                    retryMessages.size(), estimateMessageTokens(retryMessages),
                                    attempt, Map.of("reason", exc.getMessage() != null ? exc.getMessage() : "unknown")));
                    throw new RuntimeException("Compaction failed after " + attempt + " attempts", exc);
                }
                emitProgress(progressCallback, "compact_retry", trigger,
                        exc.getMessage(), attempt, "compact_retry",
                        recordCompactCheckpoint(carryoverMetadata, "compact_retry", trigger,
                                retryMessages.size(), estimateMessageTokens(retryMessages),
                                attempt, Map.of("reason", exc.getMessage() != null ? exc.getMessage() : "unknown")));
            }
        }

        if (summaryText.isEmpty()) {
            emitProgress(progressCallback, "compact_failed", trigger,
                    ERROR_MESSAGE_INCOMPLETE_RESPONSE, null, "compact_failed",
                    recordCompactCheckpoint(carryoverMetadata, "compact_failed", trigger,
                            messages.size(), preCompactTokens));
            LOG.warning("Compact summary was empty — returning original messages");
            return buildPassthroughResult(messages, trigger, "full",
                    Map.of("reason", ERROR_MESSAGE_INCOMPLETE_RESPONSE));
        }

        // Step 5: build the new message list
        String summaryContent = buildCompactSummaryMessage(summaryText, suppressFollowUp, !newer.isEmpty());
        ConversationMessage summaryMsg = ConversationMessage.fromUserText(summaryContent);

        // Post-compact hook
        List<CompactAttachment> hookAttachments = List.of();
        if (hookExecutor != null) {
            Map<String, Object> postPayload = new HashMap<>();
            postPayload.put("event", HookEvent.POST_COMPACT.name());
            postPayload.put("trigger", trigger);
            postPayload.put("model", model);
            postPayload.put("pre_compact_message_count", messages.size());
            postPayload.put("post_compact_message_count", newer.size() + 1);
            postPayload.put("pre_compact_tokens", preCompactTokens);
            postPayload.put("post_compact_tokens", estimateMessageTokens(List.of(summaryMsg)));
            postPayload.put("attachments", attachmentPaths);
            postPayload.put("discovered_tools", discoveredTools);
            if (carryoverMetadata != null) postPayload.putAll(carryoverMetadata);

            var postResult = hookExecutor.execute(HookEvent.POST_COMPACT, postPayload);
            String hookNote = postResult.reason() != null ? postResult.reason() : "";
            if (hookNote.isEmpty()) {
                hookNote = postResult.results().stream()
                        .map(r -> r.message() != null ? r.message().strip() : "")
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("\n"));
            }
            hookAttachments = createHookAttachments(hookNote);
        }

        Map<String, Object> compactMetadata = new HashMap<>();
        compactMetadata.put("trigger", trigger);
        compactMetadata.put("compact_kind", "full");
        compactMetadata.put("pre_compact_message_count", messages.size());
        compactMetadata.put("pre_compact_token_count", preCompactTokens);
        compactMetadata.put("preserve_recent", preserveRecent);
        compactMetadata.put("tokens_freed_by_microcompact", tokensFreed);
        compactMetadata.put("pre_compact_discovered_tools", discoveredTools);
        compactMetadata.put("used_head_truncation_retry", ptlRetries > 0);
        compactMetadata.put("used_context_collapse", metadataHasCheckpoint(carryoverMetadata, "query_context_collapse_end"));
        compactMetadata.put("used_session_memory", false);
        compactMetadata.put("retry_attempts", Math.max(0, streamingRetries));
        compactMetadata.put("attachments", attachmentPaths);
        if (carryoverMetadata != null) {
            if (carryoverMetadata.get("compact_checkpoints") instanceof List<?> cps) {
                compactMetadata.put("compact_checkpoints", cps);
            }
            if (carryoverMetadata.get("compact_last") instanceof Map<?, ?> cl) {
                compactMetadata.put("compact_last", cl);
            }
        }

        CompactionResult compactionResult = new CompactionResult(
                trigger, "full",
                createCompactBoundaryMessage(compactMetadata),
                List.of(summaryMsg),
                new ArrayList<>(newer),
                buildCompactAttachments(older, carryoverMetadata),
                hookAttachments,
                compactMetadata);

        compactionResult = finalizeCompactionResult(compactionResult);
        List<ConversationMessage> postCompactMessages = buildPostCompactMessages(compactionResult);
        int postCompactTokens = estimateMessageTokens(postCompactMessages);
        compactionResult.compactMetadata().put("post_compact_message_count", postCompactMessages.size());
        compactionResult.compactMetadata().put("post_compact_token_count", postCompactTokens);
        compactionResult.setBoundaryMarker(createCompactBoundaryMessage(compactionResult.compactMetadata()));

        LOG.info("Compaction done: " + messages.size() + " -> " + postCompactMessages.size()
                + " messages, ~" + preCompactTokens + " -> ~" + postCompactTokens
                + " tokens (saved ~" + (preCompactTokens - postCompactTokens) + ")");

        emitProgress(progressCallback, "compact_end", trigger,
                "Conversation compaction complete.", null, "compact_end",
                recordCompactCheckpoint(carryoverMetadata, "compact_end", trigger,
                        postCompactMessages.size(), postCompactTokens));

        return compactionResult;
    }

    // ------------------------------------------------------------------
    // Helpers: collect streaming response
    // ------------------------------------------------------------------

    private String collectStreamingResponse(StreamingApiClient apiClient, String model,
                                            String systemPrompt, List<ConversationMessage> messages)
            throws Exception {
        var future = java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
            StringBuilder collected = new StringBuilder();
            try (Stream<ApiStreamEvent> stream = apiClient.streamMessages(
                    model, systemPrompt, messages, List.of(),
                    new StreamOptions(MAX_OUTPUT_TOKENS_FOR_SUMMARY, 0.0, systemPrompt, false))) {
                stream.forEach(event -> {
                    if (event instanceof ApiStreamEvent.ContentDelta cd) {
                        collected.append(cd.text());
                    }
                });
            }
            String result = collected.toString().strip();
            if (!result.isEmpty()) return result;
            throw new RuntimeException(ERROR_MESSAGE_INCOMPLETE_RESPONSE);
        });
        try {
            return future.get(COMPACT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new java.util.concurrent.TimeoutException(
                    "Compaction timed out after " + COMPACT_TIMEOUT_SECONDS + "s");
        }
    }

    private CompactionResult buildPassthroughResult(List<ConversationMessage> messages,
                                                     String trigger, String compactKind,
                                                     Map<String, Object> extraMetadata) {
        Map<String, Object> compactMetadata = new HashMap<>();
        compactMetadata.put("trigger", trigger);
        compactMetadata.put("compact_kind", compactKind);
        compactMetadata.put("pre_compact_message_count", messages.size());
        compactMetadata.put("pre_compact_token_count", estimateMessageTokens(messages));
        if (extraMetadata != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sanitized = (Map<String, Object>) sanitizeMetadata(extraMetadata);
            compactMetadata.putAll(sanitized);
        }

        CompactionResult result = new CompactionResult(
                trigger, compactKind,
                createCompactBoundaryMessage(compactMetadata),
                List.of(), new ArrayList<>(messages), List.of(), List.of(),
                compactMetadata);
        return finalizeCompactionResult(result);
    }

    // ------------------------------------------------------------------
    // Model-aware thresholds
    // ------------------------------------------------------------------

    public static int getContextWindow(String model, Integer contextWindowTokens) {
        if (contextWindowTokens != null && contextWindowTokens > 0) return contextWindowTokens;
        if (model == null) return DEFAULT_CONTEXT_WINDOW;
        String m = model.toLowerCase();
        if (m.contains("opus") || m.contains("sonnet") || m.contains("haiku")) return 200_000;
        return DEFAULT_CONTEXT_WINDOW;
    }

    public static int getAutocompactThreshold(String model, Integer contextWindowTokens,
                                              Integer autoCompactThresholdTokens) {
        if (autoCompactThresholdTokens != null && autoCompactThresholdTokens > 0) {
            return autoCompactThresholdTokens;
        }
        int contextWindow = getContextWindow(model, contextWindowTokens);
        int reserved = Math.min(MAX_OUTPUT_TOKENS_FOR_SUMMARY, 20_000);
        return contextWindow - reserved - AUTOCOMPACT_BUFFER_TOKENS;
    }

    public static boolean shouldAutocompact(List<ConversationMessage> messages, String model,
                                            AutoCompactState state, Integer contextWindowTokens,
                                            Integer autoCompactThresholdTokens) {
        if (state.consecutiveFailures() >= MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES) return false;
        int tokenCount = estimateMessageTokens(messages);
        int threshold = getAutocompactThreshold(model, contextWindowTokens, autoCompactThresholdTokens);
        return tokenCount >= threshold;
    }

    // ------------------------------------------------------------------
    // Auto-compact orchestration
    // ------------------------------------------------------------------

    /**
     * Check if auto-compact should fire, and if so, compact.
     * Matching Python's auto_compact_if_needed().
     *
     * @return (messages, wasCompacted) — if compacted, messages is the new list.
     */
    public AutoCompactResult autoCompactIfNeeded(
            List<ConversationMessage> messages,
            StreamingApiClient apiClient,
            String model,
            String systemPrompt,
            AutoCompactState state,
            int preserveRecent,
            CompactProgressCallback progressCallback,
            boolean force,
            String trigger,
            HookExecutor hookExecutor,
            Map<String, Object> carryoverMetadata,
            Integer contextWindowTokens,
            Integer autoCompactThresholdTokens) {

        if (!force && !shouldAutocompact(messages, model, state,
                contextWindowTokens, autoCompactThresholdTokens)) {
            return new AutoCompactResult(messages, false);
        }

        LOG.info("Auto-compact triggered (failures=" + state.consecutiveFailures() + ")");
        recordCompactCheckpoint(carryoverMetadata, "query_" + trigger + "_triggered", trigger,
                messages.size(), estimateMessageTokens(messages));

        // Try microcompact first — may be enough
        List<ConversationMessage> working = applyMicrocompact(messages, DEFAULT_KEEP_RECENT);
        int tokensFreed = estimateMessageTokens(messages) - estimateMessageTokens(working);
        recordCompactCheckpoint(carryoverMetadata, "query_microcompact_end", trigger,
                working.size(), estimateMessageTokens(working),
                null, Map.of("tokens_freed", tokensFreed));

        if (tokensFreed > 0 && !force && !shouldAutocompact(working, model, state,
                contextWindowTokens, autoCompactThresholdTokens)) {
            LOG.info("Microcompact freed ~" + tokensFreed + " tokens, auto-compact no longer needed");
            return new AutoCompactResult(working, true);
        }

        // Context collapse
        List<ConversationMessage> contextCollapsed = tryContextCollapse(working, preserveRecent);
        if (contextCollapsed != null) {
            recordCompactCheckpoint(carryoverMetadata, "query_context_collapse_start", trigger,
                    working.size(), estimateMessageTokens(working));
            emitProgress(progressCallback, "context_collapse_start", trigger,
                    "Collapsing oversized context before full compaction.", null,
                    "query_context_collapse_start", Map.of());
            working = contextCollapsed;
            recordCompactCheckpoint(carryoverMetadata, "query_context_collapse_end", trigger,
                    working.size(), estimateMessageTokens(working));
            emitProgress(progressCallback, "context_collapse_end", trigger,
                    "Context collapse complete.", null, "query_context_collapse_end", Map.of());

            if (!force && !shouldAutocompact(working, model, state,
                    contextWindowTokens, autoCompactThresholdTokens)) {
                return new AutoCompactResult(working, true);
            }
        }

        // Session memory compaction
        CompactionResult sessionMemory = trySessionMemoryCompaction(
                working, Math.max(preserveRecent, SESSION_MEMORY_KEEP_RECENT), trigger, carryoverMetadata);
        if (sessionMemory != null) {
            recordCompactCheckpoint(carryoverMetadata, "query_session_memory_start", trigger,
                    working.size(), estimateMessageTokens(working));
            emitProgress(progressCallback, "session_memory_start", trigger,
                    "Condensing earlier conversation into session memory.", null,
                    "query_session_memory_start", null);
            int postMessages = buildPostCompactMessages(sessionMemory).size();
            int postTokens = estimateMessageTokens(buildPostCompactMessages(sessionMemory));
            recordCompactCheckpoint(carryoverMetadata, "query_session_memory_end", trigger,
                    postMessages, postTokens);
            emitProgress(progressCallback, "session_memory_end", trigger,
                    "Session memory condensation complete.", null,
                    "query_session_memory_end", null);
            state.setCompacted(true);
            state.incrementTurn();
            state.newTurnId();
            state.resetFailures();
            return new AutoCompactResult(buildPostCompactMessages(sessionMemory), true);
        }

        // Full compact needed
        try {
            CompactionResult result = compactConversation(
                    working, apiClient, model, systemPrompt, preserveRecent,
                    null, true, trigger, progressCallback, hookExecutor, carryoverMetadata);
            state.setCompacted(true);
            state.incrementTurn();
            state.newTurnId();
            state.resetFailures();
            return new AutoCompactResult(buildPostCompactMessages(result), true);
        } catch (Exception exc) {
            state.incrementFailures();
            recordCompactCheckpoint(carryoverMetadata, "query_" + trigger + "_failed", trigger,
                    messages.size(), estimateMessageTokens(messages));
            LOG.severe("Auto-compact failed (" + state.consecutiveFailures() + "/"
                    + MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES + "): " + exc.getMessage());
            return new AutoCompactResult(messages, false);
        }
    }

    /** Result of auto_compact_if_needed. */
    public record AutoCompactResult(List<ConversationMessage> messages, boolean wasCompacted) {}

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object obj) {
        if (obj instanceof List<?> l) {
            return l.stream().map(Object::toString).map(String::strip)
                    .filter(s -> !s.isEmpty()).toList();
        }
        return List.of();
    }
}
