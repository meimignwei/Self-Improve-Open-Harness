package com.openharness.extensions.autopilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.config.AtomicFileWriter;
import com.openharness.config.Paths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Project-level autopilot state machine managing task cards through 13-state workflow.
 * Java equivalent of Python autopilot/service.py RepoAutopilotStore (~1300 lines).
 */
public class RepoAutopilotStore {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    private final Path repoDir;
    private final Path autopilotDir;
    private final Path registryPath;
    private final Path journalPath;

    public RepoAutopilotStore(Path repoDir) {
        this.repoDir = repoDir;
        this.autopilotDir = Paths.projectAutopilotDir(repoDir);
        this.registryPath = Paths.projectAutopilotRegistryPath(repoDir);
        this.journalPath = Paths.projectRepoJournalPath(repoDir);
    }

    public List<AutopilotTypes.RepoTaskCard> loadRegistry() {
        if (!Files.exists(registryPath)) return new ArrayList<>();
        try {
            return MAPPER.readValue(registryPath.toFile(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, AutopilotTypes.RepoTaskCard.class));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public void saveRegistry(List<AutopilotTypes.RepoTaskCard> cards) {
        AtomicFileWriter.writeJson(registryPath, cards);
    }

    public int enqueueCard(AutopilotTypes.RepoTaskCard card) {
        List<AutopilotTypes.RepoTaskCard> cards = loadRegistry();

        boolean exists = cards.stream().anyMatch(c ->
                c.fingerprint() != null && c.fingerprint().equals(card.fingerprint()));
        if (exists) return cards.size();

        double score = scoreCard(card);
        AutopilotTypes.RepoTaskCard scored = new AutopilotTypes.RepoTaskCard(
                card.id(), card.fingerprint(), card.title(), card.body(),
                card.sourceKind(), card.sourceRef(), AutopilotTypes.TaskStatus.QUEUED,
                score, card.scoreReasons(), card.labels(), card.metadata(),
                card.createdAt() != null ? card.createdAt() : Instant.now(),
                Instant.now());

        cards.add(scored);
        cards.sort(Comparator.comparing(AutopilotTypes.RepoTaskCard::score).reversed());
        saveRegistry(cards);
        appendJournal(scored);
        return cards.size();
    }

    double scoreCard(AutopilotTypes.RepoTaskCard card) {
        double score = 0.0;

        if (card.labels() != null) {
            for (String label : card.labels()) {
                String l = label.toLowerCase();
                if (l.contains("bug")) score += 3.0;
                if (l.contains("urgent") || l.contains("hotfix")) score += 5.0;
                if (l.contains("feature")) score += 1.0;
            }
        }

        Instant createdAt = card.createdAt() != null ? card.createdAt() : Instant.now();
        long daysAgo = java.time.Duration.between(createdAt, Instant.now()).toDays();
        score += Math.exp(-daysAgo / 7.0);

        return score;
    }

    public AutopilotTypes.RepoTaskCard runNext() {
        List<AutopilotTypes.RepoTaskCard> cards = loadRegistry();
        AutopilotTypes.RepoTaskCard next = cards.stream()
                .filter(c -> c.status() == AutopilotTypes.TaskStatus.QUEUED)
                .max(Comparator.comparing(AutopilotTypes.RepoTaskCard::score))
                .orElse(null);
        if (next == null) return null;

        AutopilotTypes.RepoTaskCard accepted = new AutopilotTypes.RepoTaskCard(
                next.id(), next.fingerprint(), next.title(), next.body(),
                next.sourceKind(), next.sourceRef(), AutopilotTypes.TaskStatus.ACCEPTED,
                next.score(), next.scoreReasons(), next.labels(), next.metadata(),
                next.createdAt(), Instant.now());

        cards.replaceAll(c -> c.id().equals(accepted.id()) ? accepted : c);
        saveRegistry(cards);
        appendJournal(accepted);
        return accepted;
    }

    public void updateStatus(String taskId, AutopilotTypes.TaskStatus newStatus) {
        List<AutopilotTypes.RepoTaskCard> cards = loadRegistry();
        cards.replaceAll(c -> {
            if (c.id().equals(taskId)) {
                AutopilotTypes.RepoTaskCard updated = new AutopilotTypes.RepoTaskCard(
                        c.id(), c.fingerprint(), c.title(), c.body(),
                        c.sourceKind(), c.sourceRef(), newStatus,
                        c.score(), c.scoreReasons(), c.labels(), c.metadata(),
                        c.createdAt(), Instant.now());
                appendJournal(updated);
                return updated;
            }
            return c;
        });
        saveRegistry(cards);
    }

    public List<AutopilotTypes.RepoTaskCard> getQueue() {
        return loadRegistry().stream()
                .filter(c -> c.status() == AutopilotTypes.TaskStatus.QUEUED)
                .sorted(Comparator.comparing(AutopilotTypes.RepoTaskCard::score).reversed())
                .toList();
    }

    public AutopilotTypes.AutopilotPolicies loadPolicies() {
        Path policyDir = autopilotDir;
        return new AutopilotTypes.AutopilotPolicies(
                loadYamlOrEmpty(policyDir.resolve("autopilot.yml")),
                loadYamlOrEmpty(policyDir.resolve("verification.yml")),
                loadYamlOrEmpty(policyDir.resolve("release.yml")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYamlOrEmpty(Path path) {
        if (!Files.exists(path)) return Map.of();
        try {
            String content = Files.readString(path);
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> result = yaml.load(content);
            return result != null ? result : Map.of();
        } catch (IOException e) {
            return Map.of();
        }
    }

    private void appendJournal(AutopilotTypes.RepoTaskCard card) {
        try {
            String json = MAPPER.writeValueAsString(card);
            Files.writeString(journalPath, json + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    public Path repoDir() { return repoDir; }
}
