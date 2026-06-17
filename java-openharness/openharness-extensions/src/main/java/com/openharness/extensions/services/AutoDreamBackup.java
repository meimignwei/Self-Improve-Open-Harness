package com.openharness.extensions.services;

import com.openharness.config.Paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Backup, diff, and rollback helpers for auto-dream memory directories.
 * Java equivalent of Python services/autodream/backup.py.
 */
public final class AutoDreamBackup {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private AutoDreamBackup() {}

    /**
     * Python default_backup_root: return the backup root for a memory directory.
     */
    public static Path defaultBackupRoot(Path memoryDir, String appLabel) {
        memoryDir = memoryDir.toAbsolutePath().normalize();
        String pathStr = memoryDir.toString();
        if (pathStr.contains("/.ohmo/") || pathStr.contains("\\.ohmo\\")) {
            for (int i = 0; i < memoryDir.getNameCount(); i++) {
                if (".ohmo".equals(memoryDir.getName(i).toString())) {
                    return memoryDir.getRoot() != null
                            ? memoryDir.getRoot().resolve(memoryDir.subpath(0, i + 1)).resolve("backups")
                            : Path.of(".ohmo").resolve("backups");
                }
            }
        }
        String safe = appLabel != null ? appLabel.replaceAll("[^a-zA-Z0-9_\\-]", "-").replaceAll("^-+|-+$", "") : "openharness";
        if (safe.isEmpty()) safe = "openharness";
        return Paths.dataDir().resolve("memory-backups").resolve(safe);
    }

    /**
     * Python create_memory_backup: timestamped copy of memory_dir.
     */
    public static Path createMemoryBackup(Path memoryDir, Path backupRoot, String appLabel) {
        memoryDir = memoryDir.toAbsolutePath().normalize();
        Path root = backupRoot != null
                ? backupRoot.toAbsolutePath().normalize()
                : defaultBackupRoot(memoryDir, appLabel);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create backup root: " + root, e);
        }
        String timestamp = "memory-" + TIMESTAMP_FMT.format(Instant.now());
        Path backup = root.resolve(timestamp);
        int suffix = 1;
        while (Files.exists(backup)) {
            suffix++;
            backup = root.resolve(timestamp + "-" + suffix);
        }
        if (Files.exists(memoryDir)) {
            try {
                copyTreeSkipLock(memoryDir, backup);
            } catch (IOException e) {
                throw new RuntimeException("Failed to backup memory dir: " + memoryDir, e);
            }
        } else {
            try {
                Files.createDirectories(backup);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create backup dir: " + backup, e);
            }
        }
        return backup;
    }

    public static Path createMemoryBackup(Path memoryDir, String appLabel) {
        return createMemoryBackup(memoryDir, null, appLabel);
    }

    /**
     * Python diff_memory_dirs: return added/removed/changed file names.
     */
    public static Map<String, List<String>> diffMemoryDirs(Path before, Path after) {
        before = before.toAbsolutePath().normalize();
        after = after.toAbsolutePath().normalize();
        Map<String, Path> beforeFiles = globMd(before);
        Map<String, Path> afterFiles = globMd(after);
        Set<String> beforeNames = beforeFiles.keySet();
        Set<String> afterNames = afterFiles.keySet();

        List<String> added = afterNames.stream()
                .filter(n -> !beforeNames.contains(n)).sorted().toList();
        List<String> removed = beforeNames.stream()
                .filter(n -> !afterNames.contains(n)).sorted().toList();
        List<String> changed = new ArrayList<>();
        for (String name : beforeNames) {
            if (!afterNames.contains(name)) continue;
            try {
                if (Files.mismatch(beforeFiles.get(name), afterFiles.get(name)) != -1) {
                    changed.add(name);
                }
            } catch (IOException e) {
                changed.add(name);
            }
        }
        changed.sort(String::compareTo);

        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("added", added);
        result.put("removed", removed);
        result.put("changed", changed);
        return result;
    }

    /**
     * Python format_memory_diff: format a compact memory diff summary.
     */
    public static String formatMemoryDiff(Map<String, List<String>> diff) {
        List<String> lines = new ArrayList<>();
        for (String label : List.of("added", "changed", "removed")) {
            List<String> values = diff.getOrDefault(label, List.of());
            if (!values.isEmpty()) {
                lines.add(label + ": " + String.join(", ", values));
            }
        }
        return lines.isEmpty() ? "no markdown file changes" : String.join("\n", lines);
    }

    /**
     * Python latest_memory_backup: return the latest backup for a memory directory.
     */
    public static Path latestMemoryBackup(Path memoryDir, String appLabel) {
        Path root = defaultBackupRoot(memoryDir, appLabel);
        if (!Files.exists(root)) return null;
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("memory-"))
                    .max(Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Python restore_memory_backup: restore memory_dir from a backup directory.
     */
    public static void restoreMemoryBackup(Path backupDir, Path memoryDir) throws IOException {
        backupDir = backupDir.toAbsolutePath().normalize();
        memoryDir = memoryDir.toAbsolutePath().normalize();
        if (!Files.exists(backupDir) || !Files.isDirectory(backupDir)) {
            throw new IOException("Backup not found: " + backupDir);
        }
        Path tmp = memoryDir.resolveSibling("." + memoryDir.getFileName() + ".restore-tmp");
        if (Files.exists(tmp)) {
            deleteTree(tmp);
        }
        copyTree(backupDir, tmp);
        if (Files.exists(memoryDir)) {
            deleteTree(memoryDir);
        }
        Files.move(tmp, memoryDir);
    }

    // ── helpers ──

    private static Map<String, Path> globMd(Path dir) {
        Map<String, Path> result = new LinkedHashMap<>();
        if (!Files.exists(dir)) return result;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .forEach(f -> result.put(f.getFileName().toString(), f));
        } catch (IOException ignored) {
        }
        return result;
    }

    private static void copyTreeSkipLock(Path src, Path dest) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            for (Path source : stream.collect(Collectors.toList())) {
                if (source.getFileName().toString().equals(".consolidate-lock")) continue;
                Path destination = dest.resolve(src.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            for (Path source : stream.collect(Collectors.toList())) {
                Path destination = dest.resolve(src.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }
}
