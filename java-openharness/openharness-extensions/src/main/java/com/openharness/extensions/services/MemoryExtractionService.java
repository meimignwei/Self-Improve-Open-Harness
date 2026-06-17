package com.openharness.extensions.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.openharness.common.ConversationMessage;
import com.openharness.common.ContentBlock;
import com.openharness.common.OpenHarnessObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * Extracts durable memories from conversation turns using LLM.
 * Java equivalent of Python services/memory_extract/__init__.py.
 */
public class MemoryExtractionService {

    private static final Set<String> WRITE_TOOLS = Set.of("write", "edit");

    public static final String EXTRACTION_SYSTEM_PROMPT = """
            Extract durable facts as JSON: [{"name":"...","type":"user|feedback|project|reference","content":"..."}]
            Skip transient/in-progress items. Avoid duplicates with existing memories below.
            """;

    /**
     * Safety guard: skip extraction if the current turn wrote files.
     */
    public boolean hasMemoryWritesSince(List<ConversationMessage> recentMessages) {
        return recentMessages.stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.ToolUseBlock)
                .anyMatch(b -> WRITE_TOOLS.contains(((ContentBlock.ToolUseBlock) b).name()));
    }

    /**
     * Extracts memory records from conversation messages.
     * Calls LLM with extraction prompt and parses the JSON response.
     */
    public List<ExtractionRecord> extract(List<ConversationMessage> messages,
                                           String existingMemories,
                                           java.util.function.Supplier<String> llmCall) {
        if (hasMemoryWritesSince(messages)) return List.of();

        String responses = llmCall.get();
        return parseExtractionRecords(responses);
    }

    List<ExtractionRecord> parseExtractionRecords(String response) {
        try {
            return OpenHarnessObjectMapper.get().readValue(response,
                    new TypeReference<List<ExtractionRecord>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public record ExtractionRecord(String name, String type, String content) {}
}
