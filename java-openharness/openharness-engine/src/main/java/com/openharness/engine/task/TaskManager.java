package com.openharness.engine.task;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Manages background shell and agent task lifecycles.
 * Located in engine so both tools and extensions can access it.
 */
public class TaskManager {

    private static final Logger LOG = Logger.getLogger(TaskManager.class.getName());

    private final ConcurrentHashMap<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Consumer<TaskRecord>> completionListeners = new CopyOnWriteArrayList<>();
    private final Path baseDir;

    public TaskManager() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "openharness-tasks"));
    }

    public TaskManager(Path baseDir) {
        this.baseDir = baseDir;
        try {
            java.nio.file.Files.createDirectories(baseDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create task output dir", e);
        }
    }

    public TaskRecord createShellTask(String command, Path cwd, Map<String, String> env) {
        String taskId = UUID.randomUUID().toString();
        Path outputFile = baseDir.resolve(taskId + ".output");

        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile());
            if (env != null) pb.environment().putAll(env);

            Process process = pb.start();
            TaskRecord record = new TaskRecord(taskId, TaskType.LOCAL_BASH,
                    TaskStatus.RUNNING, command, cwd.toString(), outputFile.toString(),
                    Instant.now(), Instant.now(), null, null,
                    Map.of("process", process));
            tasks.put(taskId, record);
            monitorProcess(taskId, process, record);
            return record;
        } catch (Exception e) {
            LOG.severe("Failed to create shell task: " + e.getMessage());
            TaskRecord record = new TaskRecord(taskId, TaskType.LOCAL_BASH,
                    TaskStatus.FAILED, command, cwd.toString(), null,
                    Instant.now(), Instant.now(), Instant.now(), -1, Map.of());
            tasks.put(taskId, record);
            return record;
        }
    }

    public TaskRecord createAgentTask(String prompt, Path cwd) {
        return createShellTask("java -jar openharness-app.jar -p " + shellEscape(prompt),
                cwd, Map.of());
    }

    public void stopTask(String taskId) {
        TaskRecord record = tasks.get(taskId);
        if (record == null) return;

        Object processObj = record.metadata().get("process");
        if (processObj instanceof Process p && p.isAlive()) {
            p.destroy();
            try {
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) p.destroyForcibly();
        }
        tasks.put(taskId, record.withStatus(TaskStatus.KILLED).withCompletedAt(Instant.now()));
    }

    public Optional<TaskRecord> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public List<TaskRecord> listTasks() {
        return List.copyOf(tasks.values());
    }

    public String readTaskOutput(String taskId) {
        TaskRecord record = tasks.get(taskId);
        if (record == null || record.outputFile() == null) return "";
        try {
            return java.nio.file.Files.readString(Path.of(record.outputFile()));
        } catch (Exception e) {
            return "";
        }
    }

    public void onCompletion(Consumer<TaskRecord> listener) {
        completionListeners.add(listener);
    }

    private void monitorProcess(String taskId, Process process, TaskRecord record) {
        executor.submit(() -> {
            try {
                int exitCode = process.waitFor();
                TaskRecord updated = record
                        .withStatus(exitCode == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED)
                        .withCompletedAt(Instant.now())
                        .withReturnCode(exitCode);
                tasks.put(taskId, updated);
                completionListeners.forEach(l -> l.accept(updated));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static String shellEscape(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public enum TaskType { LOCAL_BASH, LOCAL_AGENT, REMOTE_AGENT }
    public enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, KILLED }

    public record TaskRecord(
            String id, TaskType type, TaskStatus status, String description,
            String cwd, String outputFile, Instant createdAt, Instant updatedAt,
            Instant completedAt, Integer returnCode, Map<String, Object> metadata) {

        public TaskRecord withStatus(TaskStatus newStatus) {
            return new TaskRecord(id, type, newStatus, description, cwd, outputFile,
                    createdAt, Instant.now(), completedAt, returnCode, metadata);
        }

        public TaskRecord withCompletedAt(Instant time) {
            return new TaskRecord(id, type, status, description, cwd, outputFile,
                    createdAt, updatedAt, time, returnCode, metadata);
        }

        public TaskRecord withReturnCode(int code) {
            return new TaskRecord(id, type, status, description, cwd, outputFile,
                    createdAt, updatedAt, completedAt, code, metadata);
        }
    }
}
