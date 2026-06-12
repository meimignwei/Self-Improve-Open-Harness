package com.openharness.config;

import com.openharness.common.OpenHarnessObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic JSON file persistence using temp-file-and-rename pattern.
 * Java equivalent of Python's atomic_write_text + exclusive_file_lock.
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {}

    /**
     * Atomically writes a JSON-serialized object to a file.
     */
    public static void writeJson(Path path, Object object) {
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
            String json = OpenHarnessObjectMapper.get()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(object);
            Files.writeString(tempPath, json, StandardCharsets.UTF_8);
            Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config to: " + path, e);
        }
    }

    /**
     * Reads a JSON file and deserializes it into the given type.
     */
    public static <T> T readJson(Path path, Class<T> type) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return OpenHarnessObjectMapper.get().readValue(json, type);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config from: " + path, e);
        }
    }
}
