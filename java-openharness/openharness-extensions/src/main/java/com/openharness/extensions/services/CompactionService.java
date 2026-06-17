package com.openharness.extensions.services;

import com.openharness.common.ConversationMessage;
import com.openharness.common.ContentBlock;
import com.openharness.common.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Three-level auto-compaction strategy.
 * 1. MicroCompact — truncate large tool outputs
 * 2. Session Memory — deterministic summary checkpoints
 * 3. Full LLM Compact — narrative summarization via API
 *
 * Java equivalent of Python services/compact/__init__.py.
 */
public class CompactionService {

    private static final int DEFAULT_SOFT_TOKEN_BUDGET = 8000;
    private static final int CHARS_PER_TOKEN = 4;
    private static final int MAX_TOOL_OUTPUT_CHARS = DEFAULT_SOFT_TOKEN_BUDGET * CHARS_PER_TOKEN;

    /**
     * Level 1: Truncate oversized tool outputs to fit the token budget.
     * Pure local computation, no LLM call.
     */
    public List<ConversationMessage> microCompact(List<ConversationMessage> messages) {
        return microCompact(messages, DEFAULT_SOFT_TOKEN_BUDGET);
    }

    public List<ConversationMessage> microCompact(List<ConversationMessage> messages, int softTokenBudget) {
        int maxChars = softTokenBudget * CHARS_PER_TOKEN;
        List<ConversationMessage> compacted = new ArrayList<>();

        for (ConversationMessage msg : messages) {
            List<ContentBlock> newContent = new ArrayList<>();
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.ToolResultBlock trb) {
                    String truncated = truncate(trb.content(), maxChars);
                    newContent.add(new ContentBlock.ToolResultBlock(
                            trb.toolUseId(), truncated, trb.isError()));
                } else {
                    newContent.add(block);
                }
            }
            compacted.add(new ConversationMessage(msg.role(), newContent));
        }

        return compacted;
    }

    /**
     * Level 2: Create a deterministic session checkpoint with goal/next_step/verified_work.
     */
    public SessionMemory createSessionCheckpoint(List<ConversationMessage> messages) {
        String goal = extractGoal(messages);
        String nextStep = extractNextStep(messages);
        String verifiedWork = extractVerifiedWork(messages);

        return new SessionMemory(goal, nextStep, verifiedWork,
                java.time.Instant.now(), messages.size());
    }

    public record SessionMemory(String goal, String nextStep, String verifiedWork,
                                 java.time.Instant timestamp, int messageCount) {}

    // ------------------------------------------------------------------
    // Level 3: LLM-based narrative summarization
    // ------------------------------------------------------------------

    private static final String LLM_COMPACT_SYSTEM_PROMPT = """
            You are a conversation summarizer. Produce a concise, structured summary \
            of the conversation below. Include: (1) the user's main goal, (2) key \
            decisions made, (3) important findings or files created/modified, \
            (4) current state / next steps. Use bullet points. Be dense — every word counts.""";

    /**
     * Level 3: LLM-based narrative summarization.
     * Calls the LLM to produce a concise summary, then replaces older messages
     * with the summary, keeping the most recent turns intact.
     *
     * @param messages     conversation to compact
     * @param llmCaller    function (systemPrompt, userPrompt) -> LLM text response
     * @param keepRecent   number of recent messages to preserve
     * @return compacted conversation with summary prepended
     */
    public List<ConversationMessage> llmCompact(
            List<ConversationMessage> messages,
            java.util.function.BiFunction<String, String, String> llmCaller,
            int keepRecent) {

        if (messages == null || messages.size() <= keepRecent + 2) {
            return messages;
        }

        String userPrompt = buildSummaryUserPrompt(messages);

        String summary;
        try {
            summary = llmCaller.apply(LLM_COMPACT_SYSTEM_PROMPT, userPrompt);
            if (summary == null || summary.isBlank()) {
                return messages;
            }
        } catch (Exception e) {
            return messages;
        }

        List<ConversationMessage> compacted = new ArrayList<>();
        compacted.add(new ConversationMessage(
                Role.USER,
                List.of(new ContentBlock.TextBlock(
                        "[Conversation Summary]\n" + summary.strip()))));

        int start = Math.max(0, messages.size() - keepRecent);
        for (int i = start; i < messages.size(); i++) {
            compacted.add(messages.get(i));
        }

        return compacted;
    }

    public List<ConversationMessage> llmCompact(
            List<ConversationMessage> messages,
            java.util.function.BiFunction<String, String, String> llmCaller) {
        return llmCompact(messages, llmCaller, 4);
    }

    private String buildSummaryUserPrompt(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Summarize this conversation:\n\n");
        for (ConversationMessage msg : messages) {
            sb.append("[").append(msg.role().name()).append("]: ");
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.TextBlock tb) {
                    sb.append(truncate(tb.text(), 1000));
                } else if (block instanceof ContentBlock.ToolUseBlock tub) {
                    sb.append("[tool:").append(tub.name()).append("]");
                } else if (block instanceof ContentBlock.ToolResultBlock trb) {
                    sb.append("[result:").append(truncate(trb.content(), 200)).append("]");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String extractGoal(List<ConversationMessage> messages) {
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.TextBlock tb && msg.role() == Role.USER) {
                    return truncate(tb.text(), 500);
                }
            }
        }
        return "";
    }

    private String extractNextStep(List<ConversationMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = messages.get(i);
            if (msg.role() == Role.ASSISTANT) {
                for (ContentBlock block : msg.content()) {
                    if (block instanceof ContentBlock.TextBlock tb) {
                        return truncate(tb.text(), 500);
                    }
                }
            }
        }
        return "";
    }

    private String extractVerifiedWork(List<ConversationMessage> messages) {
        List<String> toolResults = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.ToolResultBlock trb && !trb.isError()) {
                    toolResults.add(trb.toolUseId());
                }
            }
        }
        return String.join(", ", toolResults);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
