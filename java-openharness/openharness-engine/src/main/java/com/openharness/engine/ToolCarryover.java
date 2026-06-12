package com.openharness.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;
import com.openharness.config.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Persists important tool outputs across turns/sessions.
 */
public class ToolCarryover {

    private static final Logger LOG = Logger.getLogger(ToolCarryover.class.getName());
    private static final int MAX_CARRYOVER_ITEMS = 20;
    private static final int MAX_CONTENT_LENGTH = 5000;

    private final Path storePath;
    private final List<CarryoverItem> items;

    public ToolCarryover(Path storePath) {
        this.storePath = storePath;
        this.items = load();
    }

    public void evaluate(String toolName, ToolResult result) {
        if (result.isError()) return;
        if (result.content() == null || result.content().length() < 200) return;
        if (isCarryoverTool(toolName)) {
            add(toolName, truncate(result.content()));
        }
    }

    public String buildPromptSnippet() {
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## Carried-over context from previous work\n\n");
        for (CarryoverItem item : items) {
            sb.append("### ").append(item.toolName()).append(" (").append(item.timestamp()).append(")\n");
            sb.append(item.content()).append("\n\n");
        }
        return sb.toString();
    }

    public void clear() {
        items.clear();
        save();
    }

    private void add(String toolName, String content) {
        items.removeIf(i -> i.toolName().equals(toolName));
        items.add(new CarryoverItem(toolName, content, Instant.now().toString()));
        while (items.size() > MAX_CARRYOVER_ITEMS) {
            items.removeFirst();
        }
        save();
    }

    private String truncate(String content) {
        return content.length() <= MAX_CONTENT_LENGTH ? content
                : content.substring(0, MAX_CONTENT_LENGTH) + "\n...[truncated]";
    }

    private boolean isCarryoverTool(String toolName) {
        return List.of("read", "grep", "glob", "web_fetch", "web_search", "image_to_text").contains(toolName);
    }

    private List<CarryoverItem> load() {
        if (!Files.exists(storePath)) return new ArrayList<>();
        try {
            return OpenHarnessObjectMapper.get().readValue(storePath.toFile(),
                    new TypeReference<List<CarryoverItem>>() {});
        } catch (IOException e) {
            LOG.warning("Failed to load carryover: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private void save() {
        try {
            AtomicFileWriter.writeJson(storePath, items);
        } catch (Exception e) {
            LOG.warning("Failed to save carryover: " + e.getMessage());
        }
    }

    public record CarryoverItem(String toolName, String content, String timestamp) {}
}
