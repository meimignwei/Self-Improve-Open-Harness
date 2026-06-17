package com.openharness.extensions.memory;

import com.openharness.config.MemorySettings;
import com.openharness.config.Paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Main API for memory CRUD, search, and lifecycle management.
 * Java equivalent of Python memory/manager.py.
 */
public class MemoryManager {

    /**
     * Python MEMORY_POLICY_LINES — injected into prompts to guide LLM memory behavior.
     */
    public static final List<String> MEMORY_POLICY_LINES = List.of(
            "## Durable memory policy",
            "- Store durable memory only when the information is not cheaply derivable from current files, docs, git history, or tool output.",
            "- Use `type: user|feedback|project|reference` and optional `scope: private|project|team` frontmatter.",
            "- `MEMORY.md` is an index, not a memory body. Keep each pointer one line.",
            "- Update or remove stale contradictions instead of duplicating notes.",
            "- If the user says to ignore memory, proceed as if no memory was loaded and do not cite, apply, or mention memory contents.",
            "- Memory can be stale. Verify remembered project/code state against current files before acting on it.",
            "- Do not save secrets, credentials, private personal context in team memory, or temporary task chatter."
    );

    private final MemoryFileStore store;
    private final MemorySearch search;
    private final MemoryUsageTracker usageTracker;
    private final MemorySettings settings;

    public MemoryManager(MemoryFileStore store, MemorySearch search,
                         MemoryUsageTracker usageTracker, MemorySettings settings) {
        this.store = store;
        this.search = search;
        this.usageTracker = usageTracker;
        this.settings = settings;
    }

    public MemoryManager(Path memoryDir, MemorySettings settings) {
        this.store = new MemoryFileStore(memoryDir);
        this.usageTracker = new MemoryUsageTracker();
        this.search = new MemorySearch(usageTracker);
        this.settings = settings;
    }

    /**
     * Creates a MemoryManager using the user-level memory directory.
     */
    public static MemoryManager createUserManager(MemorySettings settings) {
        return new MemoryManager(memoryDir(), settings);
    }

    /**
     * Creates a MemoryManager for a project-specific memory directory.
     * Uses Paths.projectMemoryDir() matching Python's sha1-hash approach.
     */
    public static MemoryManager createProjectManager(Path cwd, MemorySettings settings) {
        Path dir = Paths.projectMemoryDir(cwd);
        return new MemoryManager(dir, settings);
    }

    public static Path memoryDir() {
        Path dir = Paths.dataDir().resolve("memory");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create memory dir: " + dir, e);
        }
        return dir;
    }

    // ── CRUD ──

    public MemoryEntry create(MemoryType type, String name, String description, String body) {
        return create(type, name, description, body, "knowledge");
    }

    public MemoryEntry create(MemoryType type, String name, String description, String body, String category) {
        MemoryEntry entry = MemoryEntry.create(type, name, description, body, category);
        store.save(entry);
        return entry;
    }

    /**
     * Python add_memory_entry: signature-based dedup + slug path + index update.
     */
    public MemoryEntry create(MemoryEntry entry) {
        String typeStr = entry.header().type().name().toLowerCase();
        String cat = entry.header().category() != null ? entry.header().category() : "knowledge";
        String signature = MemorySignature.compute(entry.body(), typeStr, cat);

        // Check for existing entry with same signature
        List<MemoryEntry> existing = store.findBySignature(signature);
        for (MemoryEntry e : existing) {
            if (!e.header().isExpired()) {
                MemoryEntry updated = e.withUpdatedBody(entry.body());
                Path existingPath = store.resolvePathById(e.header().id());
                if (existingPath != null) {
                    store.saveAs(updated, existingPath);
                } else {
                    store.save(updated);
                }
                return updated;
            }
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        MemoryEntry newEntry = new MemoryEntry(
                new MemoryEntry.MemoryHeader(
                        MemoryEntry.SCHEMA_VERSION,
                        MemoryEntry.generateMemoryId(now),
                        entry.header().name(), entry.header().description(),
                        entry.header().type(), entry.header().scope(),
                        cat, entry.header().importance(), entry.header().source(),
                        signature, now, now,
                        entry.header().ttlDays(), false,
                        entry.header().supersedes(), entry.header().tags()),
                entry.body());
        store.save(newEntry);
        return newEntry;
    }

    public MemoryEntry get(String id) {
        MemoryEntry entry = store.loadById(id);
        if (entry != null) {
            usageTracker.recordUsage(id);
        }
        return entry;
    }

    public MemoryEntry update(String id, String newBody) {
        MemoryEntry existing = store.loadById(id);
        if (existing == null) return null;
        MemoryEntry updated = existing.withUpdatedBody(newBody);
        Path existingPath = store.resolvePathById(id);
        if (existingPath != null) {
            store.saveAs(updated, existingPath);
        } else {
            store.save(updated);
        }
        return updated;
    }

    public MemoryEntry setImportance(String id, int importance) {
        MemoryEntry existing = store.loadById(id);
        if (existing == null) return null;
        MemoryEntry updated = existing.withImportance(importance);
        Path existingPath = store.resolvePathById(id);
        if (existingPath != null) {
            store.saveAs(updated, existingPath);
        } else {
            store.save(updated);
        }
        return updated;
    }

    public boolean delete(String id) {
        return store.delete(id);
    }

    // ── Search ──

    public List<MemoryEntry.ScoredMemory> search(String query, int topK) {
        List<MemoryEntry> memories = store.loadAll();
        return search.search(query, memories, topK);
    }

    public List<MemoryEntry> listAll() {
        var stream = store.loadAll().stream()
                .filter(m -> !m.header().isExpired());
        if (settings.maxFiles() > 0) {
            stream = stream.limit(settings.maxFiles());
        }
        return stream.toList();
    }

    public List<MemoryEntry> listByType(MemoryType type) {
        return store.findByType(type).stream()
                .filter(m -> !m.header().isExpired())
                .toList();
    }

    // ── Lifecycle ──

    /**
     * Removes expired entries (past TTL).
     */
    public int pruneExpired() {
        List<MemoryEntry> all = store.loadAll();
        int count = 0;
        for (MemoryEntry entry : all) {
            if (entry.header().isExpired()) {
                store.delete(entry.header().id());
                count++;
            }
        }
        return count;
    }

    public MemoryFileStore store() {
        return store;
    }

    public MemoryUsageTracker usageTracker() {
        return usageTracker;
    }

    // ------------------------------------------------------------------
    // Full add/remove entry — matching Python add_memory_entry / remove_memory_entry
    // ------------------------------------------------------------------

    /**
     * Python add_memory_entry: lock → signature dedup → write → update index.
     *
     * @param cwd     working directory (for lock path)
     * @param title   memory title
     * @param content memory body
     * @param type    memory type (default PROJECT)
     * @param scope   memory scope (default "project")
     * @param description optional description
     * @param tags    optional tags
     * @return path to the saved memory file
     */
    public Path addMemoryEntry(Path cwd, String title, String content,
                                MemoryType type, String scope,
                                String description, List<String> tags) {
        if (type == null) type = MemoryType.PROJECT;
        if (scope == null || scope.isBlank()) scope = "project";
        if (description == null) description = "";

        Path memoryDir = store.memoryDir();
        Path lockPath = memoryDir.resolve(".memory.lock");

        try (MemoryLock ignored = MemoryLock.acquire(lockPath)) {
            String category = "knowledge";
            String body = content.strip() + "\n";
            String signature = MemorySignature.compute(body, type.name().toLowerCase(), category);

            // Check for existing duplicate by signature
            String slug = MemoryFileStore.toSlug(title);
            Path path = null;
            for (MemoryEntry existing : store.loadAll()) {
                if (signature.equals(existing.header().signature())) {
                    Path existingPath = store.resolvePathById(existing.header().id());
                    if (existingPath != null) {
                        path = existingPath;
                        break;
                    }
                }
            }
            if (path == null) {
                path = store.resolveNextPath(slug);
            }

            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String nowText = formatInstantForMemory(now);

            String memoryId;
            String createdAt;
            List<String> supersedes = List.of();
            List<String> existingTags = List.of();
            int importance = 1;

            if (Files.exists(path)) {
                MemoryEntry existingEntry = store.parseMemoryFile(path);
                MemoryEntry.MemoryHeader h = existingEntry.header();
                createdAt = formatInstantForMemory(h.createdAt());
                memoryId = h.id() != null ? h.id() : MemoryEntry.generateMemoryId(now);
                importance = Math.max(h.importance(), 1);
                supersedes = h.supersedes() != null ? h.supersedes() : List.of();
                existingTags = h.tags() != null ? h.tags() : List.of();
            } else {
                createdAt = nowText;
                memoryId = MemoryEntry.generateMemoryId(now);
            }

            String desc = !description.isBlank() ? description.strip()
                    : MemoryEntry.firstContentLine(body);
            if (desc.isBlank()) desc = title.strip();

            List<String> finalTags = new ArrayList<>(existingTags);
            if (tags != null) {
                for (String tag : tags) {
                    String t = tag.strip();
                    if (!t.isEmpty() && !finalTags.contains(t)) {
                        finalTags.add(t);
                    }
                }
            }

            MemoryEntry.MemoryHeader header = new MemoryEntry.MemoryHeader(
                    MemoryEntry.SCHEMA_VERSION, memoryId, title.strip(), desc,
                    type, scope, category, importance, "manual",
                    signature, parseInstantForMemory(createdAt), now,
                    null, false, supersedes, finalTags);

            MemoryEntry entry = new MemoryEntry(header, body);
            store.saveAs(entry, path);

            // Update MEMORY.md index
            store.appendToIndex(title, path.getFileName().toString());

            return path;
        }
    }

    /**
     * Python remove_memory_entry: lock → set disabled → remove from index.
     */
    public boolean removeMemoryEntry(String name) {
        Path memoryDir = store.memoryDir();
        Path lockPath = memoryDir.resolve(".memory.lock");

        // Find matching entry (by name, title, or id)
        MemoryEntry target = null;
        Path targetPath = null;
        for (MemoryEntry entry : store.loadAll()) {
            MemoryEntry.MemoryHeader h = entry.header();
            Path p = store.resolvePathById(h.id());
            String stem = p != null ? p.getFileName().toString().replaceAll("\\.md$", "") : "";
            String fileName = p != null ? p.getFileName().toString() : "";
            if (name.equals(stem) || name.equals(fileName) || name.equals(h.name()) || name.equals(h.id())) {
                target = entry;
                targetPath = p;
                break;
            }
        }
        if (target == null || target.header().disabled()) return false;

        try (MemoryLock ignored = MemoryLock.acquire(lockPath)) {
            if (!Files.exists(targetPath)) return false;

            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            MemoryEntry disabled = target.withDisabled(true);

            // Preserve original timestamps and metadata
            MemoryEntry.MemoryHeader h = target.header();
            String typeStr = h.type().name().toLowerCase();
            String cat = h.category() != null ? h.category() : "knowledge";
            MemoryEntry.MemoryHeader updatedHeader = new MemoryEntry.MemoryHeader(
                    h.schemaVersion(), h.id(), h.name(), h.description(),
                    h.type(), h.scope(), cat, h.importance(), h.source(),
                    MemorySignature.compute(target.body(), typeStr, cat),
                    h.createdAt(), now,
                    h.ttlDays(), true, h.supersedes(), h.tags());
            MemoryEntry updatedEntry = new MemoryEntry(updatedHeader, target.body());
            store.saveAs(updatedEntry, targetPath);

            // Remove from index
            store.removeFromIndex(targetPath.getFileName().toString());
        }
        return true;
    }

    // Helpers for instant formatting/parsing
    private static String formatInstantForMemory(Instant instant) {
        if (instant == null) return "";
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static Instant parseInstantForMemory(String text) {
        if (text == null || text.isBlank()) return Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try {
            String s = text.strip();
            if (s.endsWith("Z")) s = s.substring(0, s.length() - 1) + "+00:00";
            return Instant.parse(s).truncatedTo(ChronoUnit.SECONDS);
        } catch (Exception e) {
            return Instant.now().truncatedTo(ChronoUnit.SECONDS);
        }
    }

    /**
     * Python load_memory_prompt: builds full memory prompt section.
     * Returns policy lines + MEMORY.md content (truncated) + directory info.
     */
    public String loadMemoryPrompt() {
        StringBuilder sb = new StringBuilder();
        for (String line : MEMORY_POLICY_LINES) {
            sb.append(line).append("\n");
        }
        sb.append("\n");

        // MEMORY.md entrypoint content (truncated)
        String entrypointContent = store.getEntrypointContent();
        if (!entrypointContent.isBlank()) {
            var result = MemoryFileStore.truncateEntrypointContent(entrypointContent);
            sb.append(result.getKey());
            if (result.getValue().wasTruncated()) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
