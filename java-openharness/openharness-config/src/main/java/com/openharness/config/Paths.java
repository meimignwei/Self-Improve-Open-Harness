package com.openharness.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Path resolution for OpenHarness configuration and data directories.
 * Follows XDG-like conventions with ~/.openharness/ as the default base directory.
 * Java equivalent of Python's config/paths.py.
 */
public final class Paths {

    private static final String DEFAULT_BASE_DIR = ".openharness";
    private static final String CONFIG_FILE_NAME = "settings.json";

    private Paths() {}

    public static Path configDir() {
        String envDir = System.getenv("OPENHARNESS_CONFIG_DIR");
        Path dir = envDir != null
                ? Path.of(envDir)
                : Path.of(System.getProperty("user.home"), DEFAULT_BASE_DIR);
        ensureDir(dir);
        return dir;
    }

    public static Path configFilePath() {
        return configDir().resolve(CONFIG_FILE_NAME);
    }

    public static Path dataDir() {
        String envDir = System.getenv("OPENHARNESS_DATA_DIR");
        Path dir = envDir != null
                ? Path.of(envDir)
                : configDir().resolve("data");
        ensureDir(dir);
        return dir;
    }

    public static Path logsDir() {
        String envDir = System.getenv("OPENHARNESS_LOGS_DIR");
        Path dir = envDir != null
                ? Path.of(envDir)
                : configDir().resolve("logs");
        ensureDir(dir);
        return dir;
    }

    public static Path sessionsDir() {
        Path dir = dataDir().resolve("sessions");
        ensureDir(dir);
        return dir;
    }

    public static Path tasksDir() {
        Path dir = dataDir().resolve("tasks");
        ensureDir(dir);
        return dir;
    }

    public static Path feedbackDir() {
        Path dir = dataDir().resolve("feedback");
        ensureDir(dir);
        return dir;
    }

    public static Path feedbackLogPath() {
        return feedbackDir().resolve("feedback.log");
    }

    public static Path cronRegistryPath() {
        return dataDir().resolve("cron_jobs.json");
    }

    public static Path homeSkillsDir() {
        Path dir = configDir().resolve("skills");
        ensureDir(dir);
        return dir;
    }

    public static Path homeAgentsDir() {
        Path dir = configDir().resolve("agents");
        ensureDir(dir);
        return dir;
    }

    public static Path homePluginsDir() {
        Path dir = configDir().resolve("plugins");
        ensureDir(dir);
        return dir;
    }

    public static Path credentialsPath() {
        return configDir().resolve("credentials.json");
    }

    public static Path projectConfigDir(Path cwd) {
        Path dir = cwd.toAbsolutePath().normalize().resolve(".openharness");
        ensureDir(dir);
        return dir;
    }

    public static Path projectIssueFile(Path cwd) {
        return projectConfigDir(cwd).resolve("issue.md");
    }

    public static Path projectPrCommentsFile(Path cwd) {
        return projectConfigDir(cwd).resolve("pr_comments.md");
    }

    public static Path projectAutopilotDir(Path cwd) {
        Path dir = projectConfigDir(cwd).resolve("autopilot");
        ensureDir(dir);
        return dir;
    }

    public static Path projectAutopilotRegistryPath(Path cwd) {
        return projectAutopilotDir(cwd).resolve("registry.json");
    }

    public static Path projectRepoJournalPath(Path cwd) {
        return projectAutopilotDir(cwd).resolve("repo_journal.jsonl");
    }

    public static Path projectActiveRepoContextPath(Path cwd) {
        return projectAutopilotDir(cwd).resolve("active_repo_context.md");
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create directory: " + dir, e);
        }
    }
}
