package com.openharness.extensions.memory;

import com.openharness.config.MemorySettings;
import com.openharness.config.Paths;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Main API for memory CRUD, search, and lifecycle management.
 * Java equivalent of Python memory/manager.py.
 */
public class MemoryManager {

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
     */
    public static MemoryManager createProjectManager(Path cwd, MemorySettings settings) {
        Path dir = Paths.projectConfigDir(cwd).resolve("memory");
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
        MemoryEntry entry = MemoryEntry.create(type, name, description, body);
        store.save(entry);
        return entry;
    }

    public MemoryEntry create(MemoryEntry entry) {
        String signature = MemorySignature.compute(entry.header().name(), entry.body());

        List<MemoryEntry> existing = store.findBySignature(signature);
        for (MemoryEntry e : existing) {
            if (!e.isExpired()) {
                MemoryEntry updated = e.withUpdatedBody(entry.body());
                store.save(updated);
                return updated;
            }
        }

        MemoryEntry newEntry = new MemoryEntry(
                new MemoryEntry.MemoryHeader(
                        2, UUID.randomUUID().toString(),
                        entry.header().name(), entry.header().description(),
                        entry.header().type(), entry.header().category(),
                        entry.header().importance(), entry.header().source(),
                        signature, Instant.now(), Instant.now(),
                        entry.header().ttlDays(), false, entry.header().supersedes()),
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
        store.save(updated);
        return updated;
    }

    public MemoryEntry setImportance(String id, int importance) {
        MemoryEntry existing = store.loadById(id);
        if (existing == null) return null;
        MemoryEntry updated = existing.withImportance(importance);
        store.save(updated);
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
        return store.loadAll().stream()
                .filter(m -> !m.isExpired())
                .toList();
    }

    public List<MemoryEntry> listByType(MemoryType type) {
        return store.findByType(type).stream()
                .filter(m -> !m.isExpired())
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
            if (entry.isExpired()) {
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
}
