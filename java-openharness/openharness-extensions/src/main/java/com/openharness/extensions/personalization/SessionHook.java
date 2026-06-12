package com.openharness.extensions.personalization;

import com.openharness.common.ConversationMessage;
import com.openharness.common.ContentBlock;
import com.openharness.config.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Post-session hook that extracts facts and updates rules documentation.
 * Java equivalent of Python personalization/session_hook.py.
 */
public class SessionHook {

    private final FactExtractor factExtractor;
    private final RulesGenerator rulesGenerator;
    private final Path factsPath;
    private final Path rulesPath;

    public SessionHook(Path dataDir) {
        this.factExtractor = new FactExtractor();
        this.rulesGenerator = new RulesGenerator();
        this.factsPath = dataDir.resolve("personalization_facts.json");
        this.rulesPath = dataDir.resolve("personalization_rules.md");
    }

    public void updateRulesFromSession(List<ConversationMessage> messages) {
        String allText = messages.stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .collect(Collectors.joining("\n"));

        if (allText.isEmpty()) return;

        List<FactExtractor.Fact> extracted = factExtractor.extract(allText);
        if (extracted.isEmpty()) return;

        List<FactExtractor.Fact> existing = loadFacts();
        List<FactExtractor.Fact> merged = mergeFacts(existing, extracted);
        saveFacts(merged);

        String rules = rulesGenerator.generate(merged);
        saveRules(rules);
    }

    List<FactExtractor.Fact> mergeFacts(List<FactExtractor.Fact> existing,
                                         List<FactExtractor.Fact> extracted) {
        Map<String, FactExtractor.Fact> merged = new LinkedHashMap<>();

        for (FactExtractor.Fact f : existing) {
            merged.putIfAbsent(f.key(), f);
        }

        for (FactExtractor.Fact f : extracted) {
            FactExtractor.Fact prev = merged.get(f.key());
            if (prev == null || f.confidence() > prev.confidence()) {
                merged.put(f.key(), f);
            }
        }

        return List.copyOf(merged.values());
    }

    @SuppressWarnings("unchecked")
    List<FactExtractor.Fact> loadFacts() {
        if (!Files.exists(factsPath)) return List.of();

        try {
            List<Map<String, Object>> raw = (List<Map<String, Object>>)
                    com.openharness.common.OpenHarnessObjectMapper.get()
                            .readValue(factsPath.toFile(), List.class);
            return raw.stream().map(m -> new FactExtractor.Fact(
                    m.get("key").toString(),
                    m.get("type").toString(),
                    m.get("label").toString(),
                    m.get("value").toString(),
                    ((Number) m.get("confidence")).doubleValue()
            )).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    void saveFacts(List<FactExtractor.Fact> facts) {
        List<Map<String, Object>> raw = facts.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.key());
            m.put("type", f.type());
            m.put("label", f.label());
            m.put("value", f.value());
            m.put("confidence", f.confidence());
            return m;
        }).toList();
        AtomicFileWriter.writeJson(factsPath, raw);
    }

    void saveRules(String rules) {
        try {
            Path tempPath = rulesPath.resolveSibling(rulesPath.getFileName() + ".tmp");
            Files.writeString(tempPath, rules);
            Files.move(tempPath, rulesPath,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save rules", e);
        }
    }
}
