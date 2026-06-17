package com.openharness.extensions.prompts;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the environment context section of the system prompt.
 * Java equivalent of Python's environment.py _format_environment_section().
 */
public final class EnvironmentInfoBuilder {

    private EnvironmentInfoBuilder() {}

    public static String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Environment\n");

        // OS — map Java os.name to Python-style names
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        if (osName.startsWith("Mac")) {
            osName = "macOS";
        } else if (osName.contains("Linux")) {
            osName = "Linux";
        }
        sb.append("- OS: ").append(osName).append(" ").append(osVersion).append("\n");

        // Architecture
        sb.append("- Architecture: ").append(System.getProperty("os.arch")).append("\n");

        // Shell — just the name, not full path (like Python's Path(shell).name)
        String shell = detectShell();
        sb.append("- Shell: ").append(shell).append("\n");

        // Working directory
        sb.append("- Working directory: ").append(Path.of("").toAbsolutePath()).append("\n");

        // Date
        sb.append("- Date: ")
                .append(ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .append("\n");

        // Java version (equivalent to Python's python_version)
        sb.append("- Java: ").append(System.getProperty("java.version")).append("\n");

        // Java executable (equivalent to Python's python_executable)
        String javaHome = System.getProperty("java.home");
        sb.append("- Java executable: ").append(javaHome).append("/bin/java\n");

        // Git — single line format: "Git: yes (branch: main)"
        GitInfo git = detectGitInfo();
        if (git.isRepo) {
            String gitLine = "- Git: yes";
            if (git.branch != null && !git.branch.isEmpty()) {
                gitLine += " (branch: " + git.branch + ")";
            }
            sb.append(gitLine).append("\n");
        }

        return sb.toString();
    }

    private static String detectShell() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isEmpty()) {
            return Path.of(shell).getFileName().toString();
        }
        return "unknown";
    }

    private static GitInfo detectGitInfo() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes()).strip();
            if (p.waitFor() != 0 || !"true".equals(result)) {
                return new GitInfo(false, null);
            }
        } catch (Exception e) {
            return new GitInfo(false, null);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String branch = new String(p.getInputStream().readAllBytes()).strip();
            p.waitFor();
            return new GitInfo(true, branch);
        } catch (Exception e) {
            return new GitInfo(true, null);
        }
    }

    private record GitInfo(boolean isRepo, String branch) {}
}
