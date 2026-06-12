package com.openharness.extensions.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages bridge child session processes.
 * Java equivalent of Python bridge/manager.py BridgeSessionManager.
 */
public class BridgeSessionManager {

    private static final BridgeSessionManager INSTANCE = new BridgeSessionManager();

    private final ConcurrentHashMap<String, SessionHandle> sessions = new ConcurrentHashMap<>();

    public static BridgeSessionManager getInstance() {
        return INSTANCE;
    }

    public SessionHandle spawn(String sessionId, String command, Path cwd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process process = pb.start();

        Path logFile = getBridgeDataDir().resolve(sessionId + ".log");
        Files.createDirectories(logFile.getParent());
        copyProcessOutput(process, logFile);

        SessionHandle handle = new SessionHandle(sessionId, process, cwd, Instant.now(), logFile);
        sessions.put(sessionId, handle);
        return handle;
    }

    public List<SessionHandle> listSessions() {
        return List.copyOf(sessions.values());
    }

    public String readOutput(String sessionId) throws IOException {
        SessionHandle handle = sessions.get(sessionId);
        if (handle == null || !Files.exists(handle.logFile())) return "";

        byte[] bytes = Files.readAllBytes(handle.logFile());
        String content = new String(bytes, StandardCharsets.UTF_8);
        int maxLen = 100_000;
        if (content.length() > maxLen) {
            content = content.substring(content.length() - maxLen);
        }
        return content;
    }

    public void stop(String sessionId) {
        SessionHandle handle = sessions.remove(sessionId);
        if (handle == null) return;

        Process process = handle.process();
        if (process.isAlive()) {
            process.destroy();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Path getBridgeDataDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".openharness", "data", "bridge");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    private void copyProcessOutput(Process process, Path logFile) {
        Thread.startVirtualThread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    Files.write(logFile, java.util.Arrays.copyOf(buf, n),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (IOException ignored) {}
        });
    }

    public record SessionHandle(
            String sessionId,
            Process process,
            Path cwd,
            Instant startedAt,
            Path logFile
    ) {}
}
