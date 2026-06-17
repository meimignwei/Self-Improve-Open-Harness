package com.openharness.extensions.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manage bridge-run child sessions and capture their output.
 * Java equivalent of Python bridge/manager.py BridgeSessionManager.
 */
public class BridgeSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(BridgeSessionManager.class);

    private static volatile BridgeSessionManager instance;

    private final ConcurrentHashMap<String, SessionHandle> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> commands = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Path> outputPaths = new ConcurrentHashMap<>();

    public static BridgeSessionManager getInstance() {
        if (instance == null) {
            synchronized (BridgeSessionManager.class) {
                if (instance == null) {
                    instance = new BridgeSessionManager();
                }
            }
        }
        return instance;
    }

    public SessionHandle spawn(String sessionId, String command, Path cwd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process process = pb.start();

        Path outputDir = getBridgeDataDir();
        Files.createDirectories(outputDir);
        Path logFile = outputDir.resolve(sessionId + ".log");
        Files.writeString(logFile, "", StandardCharsets.UTF_8);

        SessionHandle handle = new SessionHandle(sessionId, process, cwd, Instant.now(), logFile);
        sessions.put(sessionId, handle);
        commands.put(sessionId, command);
        outputPaths.put(sessionId, logFile);

        copyProcessOutput(sessionId, handle, logFile);

        logger.debug("Spawned bridge session {} (cwd={})", sessionId, cwd);
        return handle;
    }

    public List<BridgeSessionRecord> listSessions() {
        List<BridgeSessionRecord> items = new ArrayList<>();
        for (var entry : sessions.entrySet()) {
            String sessionId = entry.getKey();
            SessionHandle handle = entry.getValue();
            Process process = handle.process();

            String status;
            if (process.isAlive()) {
                status = "running";
            } else if (process.exitValue() == 0) {
                status = "completed";
            } else {
                status = "failed";
            }

            items.add(new BridgeSessionRecord(
                    sessionId,
                    commands.getOrDefault(sessionId, ""),
                    handle.cwd().toString(),
                    (int) process.pid(),
                    status,
                    handle.startedAt().toEpochMilli() / 1000.0,
                    outputPaths.getOrDefault(sessionId, Path.of("")).toString()));
        }
        items.sort(Comparator.comparing(BridgeSessionRecord::startedAt).reversed());
        return items;
    }

    public String readOutput(String sessionId, int maxBytes) throws IOException {
        Path path = outputPaths.get(sessionId);
        if (path == null || !Files.exists(path)) return "";

        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.length() > maxBytes) {
            return content.substring(content.length() - maxBytes);
        }
        return content;
    }

    public String readOutput(String sessionId) throws IOException {
        return readOutput(sessionId, 12000);
    }

    public void stop(String sessionId) {
        SessionHandle handle = sessions.get(sessionId);
        if (handle == null) {
            throw new IllegalArgumentException("Unknown bridge session: " + sessionId);
        }
        Process process = handle.process();
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        sessions.remove(sessionId);
        commands.remove(sessionId);
        outputPaths.remove(sessionId);
        logger.debug("Stopped bridge session {}", sessionId);
    }

    private static Path getBridgeDataDir() {
        return Path.of(System.getProperty("user.home"), ".openharness", "data", "bridge");
    }

    private void copyProcessOutput(String sessionId, SessionHandle handle, Path logFile) {
        Thread.startVirtualThread(() -> {
            try (InputStream in = handle.process().getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    Files.write(logFile, java.util.Arrays.copyOf(buf, n),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (IOException e) {
                logger.debug("Bridge session {} output stream ended: {}", sessionId, e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    public record SessionHandle(
            String sessionId,
            Process process,
            Path cwd,
            Instant startedAt,
            Path logFile) {
    }

    public record BridgeSessionRecord(
            String sessionId,
            String command,
            String cwd,
            int pid,
            String status,
            double startedAt,
            String outputPath) {
    }
}
