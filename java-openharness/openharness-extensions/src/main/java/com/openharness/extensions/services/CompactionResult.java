package com.openharness.extensions.services;

import com.openharness.common.ConversationMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured compaction result matching Python's CompactionResult dataclass.
 */
public class CompactionResult {

    private final String trigger;
    private final String compactKind;
    private ConversationMessage boundaryMarker;
    private List<ConversationMessage> summaryMessages;
    private List<ConversationMessage> messagesToKeep;
    private List<CompactAttachment> attachments;
    private List<CompactAttachment> hookResults;
    private final Map<String, Object> compactMetadata;

    public CompactionResult(String trigger, String compactKind,
                            ConversationMessage boundaryMarker,
                            List<ConversationMessage> summaryMessages,
                            List<ConversationMessage> messagesToKeep,
                            List<CompactAttachment> attachments,
                            List<CompactAttachment> hookResults,
                            Map<String, Object> compactMetadata) {
        this.trigger = trigger;
        this.compactKind = compactKind;
        this.boundaryMarker = boundaryMarker;
        this.summaryMessages = summaryMessages != null ? new ArrayList<>(summaryMessages) : new ArrayList<>();
        this.messagesToKeep = messagesToKeep != null ? new ArrayList<>(messagesToKeep) : new ArrayList<>();
        this.attachments = attachments != null ? new ArrayList<>(attachments) : new ArrayList<>();
        this.hookResults = hookResults != null ? new ArrayList<>(hookResults) : new ArrayList<>();
        this.compactMetadata = compactMetadata != null ? new HashMap<>(compactMetadata) : new HashMap<>();
    }

    public String trigger() { return trigger; }
    public String compactKind() { return compactKind; }

    public ConversationMessage boundaryMarker() { return boundaryMarker; }
    public void setBoundaryMarker(ConversationMessage m) { this.boundaryMarker = m; }

    public List<ConversationMessage> summaryMessages() { return summaryMessages; }
    public void setSummaryMessages(List<ConversationMessage> m) { this.summaryMessages = m; }

    public List<ConversationMessage> messagesToKeep() { return messagesToKeep; }
    public void setMessagesToKeep(List<ConversationMessage> m) { this.messagesToKeep = m; }

    public List<CompactAttachment> attachments() { return attachments; }
    public List<CompactAttachment> hookResults() { return hookResults; }

    public Map<String, Object> compactMetadata() { return compactMetadata; }
}
