package com.openharness.extensions.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Schema migration for memory entries.
 * Java equivalent of Python memory/migrate.py.
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
                            store.save(migrated);
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

    MemoryEntry migrateEntry(MemoryEntry entry, int fromVersion) {
        if (entry.header().schemaVersion() >= 2) {
            return entry;
        }

        // v1 → v2: add missing fields
        MemoryEntry.MemoryHeader h = entry.header();
        MemoryEntry.MemoryHeader newHeader = new MemoryEntry.MemoryHeader(
                2,
                h.id() != null ? h.id() : UUID.randomUUID().toString(),
                h.name(),
                h.description(),
                h.type() != null ? h.type() : MemoryType.USER,
                h.category(),
                h.importance() > 0 ? h.importance() : 5,
                h.source(),
                h.signature() != null ? h.signature()
                        : MemorySignature.compute(h.name(), entry.body()),
                h.createdAt() != null ? h.createdAt() : Instant.now(),
                h.updatedAt() != null ? h.updatedAt() : Instant.now(),
                h.ttlDays(),
                h.disabled(),
                h.supersedes());
        return new MemoryEntry(newHeader, entry.body());
    }
}
