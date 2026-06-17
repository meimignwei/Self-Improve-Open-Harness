package com.openharness.extensions.memory;

import com.openharness.config.Paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Team memory vault helpers and safety guards.
 * Java equivalent of Python memory/team.py.
 */
public final class MemoryTeamService {

    private static final String TEAM_DIR_NAME = "team";
    private static final String MEMORY_INDEX = "MEMORY.md";

    /**
     * Python SECRET_RULES — 6 regex patterns for secret detection.
     */
    private static final List<SecretRule> SECRET_RULES = List.of(
            new SecretRule("private-key", "private key",
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new SecretRule("aws-access-key", "AWS access key",
                    Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new SecretRule("github-token", "GitHub token",
                    Pattern.compile("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b")),
            new SecretRule("openai-key", "OpenAI API key",
                    Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b")),
            new SecretRule("anthropic-key", "Anthropic API key",
                    Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}\\b")),
            new SecretRule("generic-secret", "secret assignment",
                    Pattern.compile("(?i)\\b(secret|token|api[_-]?key|password)\\s*[:=]\\s*['\"]?[^'\"\\s]{12,}"))
    );

    private record SecretRule(String ruleId, String label, Pattern pattern) {}

    public record SecretMatch(String ruleId, String label) {}

    private MemoryTeamService() {}

    // ------------------------------------------------------------------
    // Path resolution
    // ------------------------------------------------------------------

    /**
     * Python get_team_memory_dir: project-local shared team memory vault.
     */
    public static Path getTeamMemoryDir(Path cwd) {
        return Paths.projectMemoryDir(cwd).resolve(TEAM_DIR_NAME);
    }

    /**
     * Python ensure_team_memory_vault: create and return the team memory vault.
     */
    public static Path ensureTeamMemoryVault(Path cwd) {
        Path teamDir = getTeamMemoryDir(cwd);
        try {
            Files.createDirectories(teamDir);
            Path entrypoint = teamDir.resolve(MEMORY_INDEX);
            if (!Files.exists(entrypoint)) {
                Files.writeString(entrypoint, "# Memory Index\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create team memory vault: " + teamDir, e);
        }
        return teamDir;
    }

    // ------------------------------------------------------------------
    // Path validation
    // ------------------------------------------------------------------

    /**
     * Python validate_team_memory_write_path: validate against traversal and symlink escape.
     */
    public static java.util.AbstractMap.SimpleEntry<Path, String> validateTeamMemoryWritePath(
            Path cwd, String candidate) {
        Path teamDir = ensureTeamMemoryVault(cwd).toAbsolutePath().normalize();
        Path path = Path.of(candidate.startsWith("~") ? candidate.replace("~", System.getProperty("user.home")) : candidate);
        if (!path.isAbsolute()) {
            path = teamDir.resolve(path);
        }
        Path resolved = path.toAbsolutePath().normalize();
        if (!resolved.startsWith(teamDir)) {
            return new java.util.AbstractMap.SimpleEntry<>(null,
                    "Path escapes team memory directory: " + candidate);
        }
        // Symlink check: resolve parent hierarchy
        Path deepest = resolved.getParent();
        while (deepest != null && !Files.exists(deepest)) {
            deepest = deepest.getParent();
            if (deepest == null) break;
        }
        if (deepest != null && Files.exists(deepest)) {
            try {
                Path deepestReal = deepest.toRealPath();
                if (!deepestReal.startsWith(teamDir.toAbsolutePath())) {
                    return new java.util.AbstractMap.SimpleEntry<>(null,
                            "Path escapes team memory directory via symlink: " + candidate);
                }
            } catch (IOException ignored) {
            }
        }
        return new java.util.AbstractMap.SimpleEntry<>(resolved, null);
    }

    // ------------------------------------------------------------------
    // Secret scanning
    // ------------------------------------------------------------------

    /**
     * Python scan_for_secrets: return possible secrets without exposing matched values.
     */
    public static List<SecretMatch> scanForSecrets(String content) {
        List<SecretMatch> matches = new ArrayList<>();
        for (SecretRule rule : SECRET_RULES) {
            if (rule.pattern().matcher(content).find()) {
                matches.add(new SecretMatch(rule.ruleId(), rule.label()));
            }
        }
        return matches;
    }

    /**
     * Python check_team_memory_secrets: return error when content appears sensitive.
     */
    public static String checkTeamMemorySecrets(String content) {
        List<SecretMatch> matches = scanForSecrets(content);
        if (matches.isEmpty()) return null;
        String labels = matches.stream().map(SecretMatch::label)
                .reduce((a, b) -> a + ", " + b).orElse("");
        return "Content contains potential secrets (" + labels + ") and cannot be written to team memory. "
                + "Team memory is shared with project collaborators.";
    }
}
