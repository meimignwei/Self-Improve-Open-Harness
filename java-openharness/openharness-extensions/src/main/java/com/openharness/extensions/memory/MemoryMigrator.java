package com.openharness.extensions.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Schema migration for memory entries.
 * Java equivalent of Python memory/migrate.py.
 *
 * Migrates from older schema versions to SCHEMA_VERSION=1 with full 18-field frontmatter.
 */
public class MemoryMigrator {

    private final MemoryFileStore store;

    public MemoryMigrator(MemoryFileStore store) {
        this.store = store;
    }

    public void migrate(int fromVersion) {
        Path memoryDir = store.memoryDir();
        backup(memoryDir);

        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .forEach(f -> {
                        try {
                            MemoryEntry entry = store.parseMemoryFile(f);
                            MemoryEntry migrated = migrateEntry(entry, fromVersion);
                            store.saveAs(migrated, f);
                        } catch (Exception e) {
                            System.err.println("Failed to migrate: " + f + " - " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Migration failed", e);
        }
    }

    void backup(Path memoryDir) {
        Path backupDir = memoryDir.resolveSibling(
                memoryDir.getFileName() + "_backup_" + System.currentTimeMillis());
        try {
            Files.createDirectories(backupDir);
            try (Stream<Path> files = Files.list(memoryDir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".md"))
                        .forEach(f -> {
                            try {
                                Files.copy(f, backupDir.resolve(f.getFileName()),
                                        StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                System.err.println("Backup failed for: " + f);
                            }
                        });
            }
        } catch (IOException e) {
            throw new RuntimeException("Backup failed", e);
        }
    }

    /**
     * Migrate an entry to schema v1 with complete 18-field frontmatter.
     * Uses memory_metadata_from_path semantics: preserves existing values,
     * fills in defaults for missing fields.
     */
    MemoryEntry migrateEntry(MemoryEntry entry, int fromVersion) {
        MemoryEntry.MemoryHeader h = entry.header();

        if (h.schemaVersion() == MemoryEntry.SCHEMA_VERSION && h.scope() != null && h.tags() != null) {
            return entry;
        }

        String typeStr = h.type() != null ? h.type().name().toLowerCase() : "project";
        String cat = h.category() != null ? h.category() : "knowledge";
        String sig = h.signature() != null ? h.signature()
                : MemorySignature.compute(entry.body(), typeStr, cat);

        MemoryEntry.MemoryHeader newHeader = new MemoryEntry.MemoryHeader(
                MemoryEntry.SCHEMA_VERSION,
                h.id() != null ? h.id() : MemoryEntry.generateMemoryId(),
                h.name(),
                h.description(),
                h.type() != null ? h.type() : MemoryType.PROJECT,
                h.scope() != null ? h.scope() : "project",
                cat,
                h.importance() > 0 ? h.importance() : 5,
                h.source(),
                sig,
                h.createdAt() != null ? h.createdAt() : Instant.now(),
                h.updatedAt() != null ? h.updatedAt() : Instant.now(),
                h.ttlDays(),
                h.disabled(),
                h.supersedes() != null ? h.supersedes() : List.of(),
                h.tags() != null ? h.tags() : List.of());
        return new MemoryEntry(newHeader, entry.body());
    }
}
