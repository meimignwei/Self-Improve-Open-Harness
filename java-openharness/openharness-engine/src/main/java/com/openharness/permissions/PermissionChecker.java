package com.openharness.permissions;

import com.openharness.config.PermissionSettings;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Evaluate tool usage against the configured permission mode and rules.
 * Java equivalent of Python's PermissionChecker.
 */
public class PermissionChecker {

    private static final Logger LOG = Logger.getLogger(PermissionChecker.class.getName());

    private final PermissionSettings settings;
    private final List<PathRule> pathRules = new ArrayList<>();

    public PermissionChecker(PermissionSettings settings) {
        this.settings = settings;
        for (var rule : settings.pathRules()) {
            String pattern = rule.pattern();
            if (pattern != null && !pattern.isBlank()) {
                pathRules.add(new PathRule(pattern.strip(), rule.allow()));
            } else {
                LOG.warning("Skipping path rule with missing or empty pattern: " + rule);
            }
        }
    }

    /**
     * Return whether the tool may run immediately.
     */
    public PermissionDecision evaluate(
            String toolName,
            boolean isReadOnly,
            String filePath,
            String command) {

        // Built-in sensitive path protection — always active
        if (filePath != null) {
            for (String candidatePath : policyMatchPaths(filePath)) {
                for (PathRule sensitive : PathRule.SENSITIVE_PATHS) {
                    if (fnmatch(candidatePath, sensitive.pattern())) {
                        return PermissionDecision.deny(
                                "Access denied: " + filePath + " is a sensitive credential path "
                                        + "(matched built-in pattern '" + sensitive.pattern() + "')");
                    }
                }
            }
        }

        // Explicit tool deny list
        if (settings.deniedTools().contains(toolName)) {
            return PermissionDecision.deny(toolName + " is explicitly denied");
        }

        // Explicit tool allow list
        if (settings.allowedTools().contains(toolName)) {
            return PermissionDecision.allow(toolName + " is explicitly allowed");
        }

        // Check path-level rules
        if (filePath != null && !pathRules.isEmpty()) {
            for (String candidatePath : policyMatchPaths(filePath)) {
                for (PathRule rule : pathRules) {
                    if (fnmatch(candidatePath, rule.pattern())) {
                        if (!rule.allow()) {
                            return PermissionDecision.deny(
                                    "Path " + filePath + " matches deny rule: " + rule.pattern());
                        }
                    }
                }
            }
        }

        // Check command deny patterns
        if (command != null && !settings.deniedCommands().isEmpty()) {
            for (String pattern : settings.deniedCommands()) {
                if (fnmatch(command, pattern)) {
                    return PermissionDecision.deny("Command matches deny pattern: " + pattern);
                }
            }
        }

        // Full auto: allow everything
        if (settings.mode().equals(PermissionMode.FULL_AUTO.value())) {
            return PermissionDecision.allow("Auto mode allows all tools");
        }

        // Read-only tools always allowed
        if (isReadOnly) {
            return PermissionDecision.allow("read-only tools are allowed");
        }

        // Plan mode: block mutating tools
        if (settings.mode().equals(PermissionMode.PLAN.value())) {
            return PermissionDecision.deny(
                    "Plan mode blocks mutating tools until the user exits plan mode");
        }

        // Default mode: require confirmation for mutating tools
        String reason = "Mutating tools require user confirmation in default mode. "
                + "Approve the prompt when asked, or run /permissions full_auto "
                + "if you want to allow them for this session.";
        String bashHint = bashPermissionHint(command);
        if (!bashHint.isEmpty()) {
            reason = reason + " " + bashHint;
        }
        return PermissionDecision.confirm(reason);
    }

    /**
     * Return path forms that should participate in policy matching.
     */
    private static String[] policyMatchPaths(String filePath) {
        String normalized = filePath.replaceAll("/+$", "");
        if (normalized.isEmpty()) return new String[]{filePath};
        return new String[]{normalized, normalized + "/"};
    }

    /**
     * Simple fnmatch-style glob matching using Java NIO PathMatcher.
     */
    static boolean fnmatch(String path, String pattern) {
        try {
            PathMatcher matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern);
            return matcher.matches(java.nio.file.Path.of(path));
        } catch (Exception e) {
            return false;
        }
    }

    private static String bashPermissionHint(String command) {
        if (command == null) return "";
        String lowered = command.toLowerCase();
        String[] installMarkers = {
                "npm install", "pnpm install", "yarn install", "bun install",
                "pip install", "uv pip install", "poetry install", "cargo install",
                "create-next-app", "npm create ", "pnpm create ", "yarn create ",
                "bun create ", "npx create-", "npm init ", "pnpm init ", "yarn init "
        };
        for (String marker : installMarkers) {
            if (lowered.contains(marker)) {
                return "Package installation and scaffolding commands change the workspace, "
                        + "so they will not run automatically in default mode.";
            }
        }
        return "";
    }
}
