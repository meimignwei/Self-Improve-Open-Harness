package com.openharness.auth;

import com.openharness.config.Paths;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dual-backend credential storage: file-based + optional system keyring.
 * Java equivalent of Python's auth/storage.py.
 */
public final class CredentialStorage {

    private static final Path CREDENTIALS_FILE = Paths.configDir().resolve("credentials.json");

    private CredentialStorage() {}

    /**
     * Load all stored credentials.
     */
    public static Map<String, StoredCredential> loadAll() {
        if (!Files.exists(CREDENTIALS_FILE)) {
            return new LinkedHashMap<>();
        }
        try {
            String json = Files.readString(CREDENTIALS_FILE, StandardCharsets.UTF_8);
            var mapper = OpenHarnessObjectMapper.get();
            var mapType = mapper.getTypeFactory().constructMapType(
                    LinkedHashMap.class, String.class, StoredCredential.class);
            Map<String, StoredCredential> result = mapper.readValue(json, mapType);
            return result != null ? result : new LinkedHashMap<>();
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Store a credential by key.
     */
    public static void store(String key, StoredCredential credential) {
        Map<String, StoredCredential> all = loadAll();
        all.put(key, credential);
        save(all);
    }

    /**
     * Delete a credential by key.
     */
    public static void delete(String key) {
        Map<String, StoredCredential> all = loadAll();
        all.remove(key);
        save(all);
    }

    private static void save(Map<String, StoredCredential> credentials) {
        try {
            Path parent = CREDENTIALS_FILE.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            String json = OpenHarnessObjectMapper.get()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(credentials);
            Files.writeString(CREDENTIALS_FILE, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save credentials", e);
        }
    }

    /**
     * A stored credential record.
     */
    public record StoredCredential(
            String authKind,
            String value,
            String source,
            String state) {

        public StoredCredential {
            if (state == null || state.isBlank()) {
                state = "configured";
            }
        }
    }
}
