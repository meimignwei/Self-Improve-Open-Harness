package com.openharness.ohmo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages ~/.ohmo personal workspace initialization and health checks.
 * Java equivalent of Python ohmo/workspace.py.
 */
public class WorkspaceManager {

    private static final String WORKSPACE_DIRNAME = ".ohmo";
    private static final String SOUL_TEMPLATE = "# ohmo Soul\n\nDescribe your agent's personality, values, and behavioral guidelines here.\n";
    private static final String USER_TEMPLATE = "# User Profile\n\nDescribe yourself, your preferences, and your working style here.\n";
    private static final String IDENTITY_TEMPLATE = "# ohmo Identity\n\nDefine your agent's identity and role here.\n";
    private static final String MEMORY_INDEX_TEMPLATE = "# ohmo Memory\n\nPersonal memory index.\n";

    public Path resolve(String workspace) {
        if (workspace != null && !workspace.isEmpty()) return Path.of(workspace).toAbsolutePath();
        String env = System.getenv("OHMO_WORKSPACE");
        if (env != null && !env.isEmpty()) return Path.of(env).toAbsolutePath();
        return Path.of(System.getProperty("user.home"), WORKSPACE_DIRNAME);
    }

    public Path initialize(Path root) {
        try {
            Files.createDirectories(root);
            mkdir(root, "memory");
            mkdir(root, "skills");
            mkdir(root, "plugins");
            mkdir(root, "groups");
            mkdir(root, "sessions");
            mkdir(root, "logs");
            mkdir(root, "attachments");

            writeIfMissing(root.resolve("soul.md"), SOUL_TEMPLATE);
            writeIfMissing(root.resolve("user.md"), USER_TEMPLATE);
            writeIfMissing(root.resolve("identity.md"), IDENTITY_TEMPLATE);
            writeIfMissing(root.resolve("memory").resolve("MEMORY.md"), MEMORY_INDEX_TEMPLATE);
            writeIfMissing(root.resolve("state.json"), "{\"app\": \"ohmo\"}");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize workspace: " + root, e);
        }
        return root;
    }

    public Map<String, Boolean> healthCheck(Path root) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        results.put("workspace", Files.exists(root));
        results.put("soul", Files.exists(root.resolve("soul.md")));
        results.put("user", Files.exists(root.resolve("user.md")));
        results.put("identity", Files.exists(root.resolve("identity.md")));
        results.put("memory_dir", Files.exists(root.resolve("memory")));
        results.put("gateway_config", Files.exists(root.resolve("gateway.json")));
        return results;
    }

    private void mkdir(Path parent, String name) throws IOException {
        Files.createDirectories(parent.resolve(name));
    }

    private void writeIfMissing(Path file, String content) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }
}
