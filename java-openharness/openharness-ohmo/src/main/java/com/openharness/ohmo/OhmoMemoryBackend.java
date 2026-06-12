package com.openharness.ohmo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ohmo personal memory backend — independent from project-level MEMORY.md.
 * Java equivalent of Python ohmo/memory.py.
 */
public class OhmoMemoryBackend {

    private final Path memoryDir;

    public OhmoMemoryBackend(Path memoryDir) {
        this.memoryDir = memoryDir;
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory dir", e);
        }
    }

    public List<MemoryItem> listEntries() {
        List<MemoryItem> entries = new ArrayList<>();
        try (var files = Files.list(memoryDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .forEach(f -> {
                        String content = readText(f);
                        if (content != null) {
                            entries.add(new MemoryItem(
                                    f.getFileName().toString().replace(".md", ""), content));
                        }
                    });
        } catch (IOException ignored) {}
        return entries;
    }

    public void addEntry(String name, String content) {
        Path file = memoryDir.resolve(name + ".md");
        try {
            Path tempPath = memoryDir.resolve(name + ".tmp");
            Files.writeString(tempPath, content, StandardCharsets.UTF_8);
            Files.move(tempPath, file,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save memory: " + name, e);
        }
    }

    public void removeEntry(String name) {
        try {
            Files.deleteIfExists(memoryDir.resolve(name + ".md"));
        } catch (IOException ignored) {}
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public record MemoryItem(String name, String content) {}
}
