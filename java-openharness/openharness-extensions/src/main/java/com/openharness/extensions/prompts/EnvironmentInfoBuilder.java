package com.openharness.extensions.prompts;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the environment context section of the system prompt.
 * Java equivalent of Python's environment.py.
 */
public final class EnvironmentInfoBuilder {

    private EnvironmentInfoBuilder() {}

    public static String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n# Environment\n");

        // OS
        sb.append("- Operating System: ").append(System.getProperty("os.name"))
                .append(" ").append(System.getProperty("os.version")).append("\n");

        // Shell
        String shell = System.getenv("SHELL");
        sb.append("- Shell: ").append(shell != null ? shell : "unknown").append("\n");

        // Java
        sb.append("- Java Version: ").append(System.getProperty("java.version")).append("\n");

        // Working directory
        sb.append("- Working Directory: ").append(Path.of("").toAbsolutePath()).append("\n");

        // Git
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "config", "--get", "user.name");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String gitUser = new String(p.getInputStream().readAllBytes()).strip();
            if (!gitUser.isEmpty()) {
                sb.append("- Git User: ").append(gitUser).append("\n");
            }

            pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.redirectErrorStream(true);
            p = pb.start();
            String branch = new String(p.getInputStream().readAllBytes()).strip();
            if (!branch.isEmpty()) {
                sb.append("- Current Branch: ").append(branch).append("\n");
            }
        } catch (Exception e) {
            // Git not available
        }

        // Date
        sb.append("- Current Date: ")
                .append(ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .append("\n");

        return sb.toString();
    }
}
