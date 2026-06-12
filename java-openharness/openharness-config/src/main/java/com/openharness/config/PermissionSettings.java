package com.openharness.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Permission mode configuration.
 * Java equivalent of Python's PermissionSettings Pydantic model.
 */
public class PermissionSettings {

    private String mode = "default";
    private List<String> allowedTools = new ArrayList<>();
    private List<String> deniedTools = new ArrayList<>();
    private List<PathRuleConfig> pathRules = new ArrayList<>();
    private List<String> deniedCommands = new ArrayList<>();

    public String mode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public List<String> allowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }

    public List<String> deniedTools() { return deniedTools; }
    public void setDeniedTools(List<String> deniedTools) { this.deniedTools = deniedTools; }

    public List<PathRuleConfig> pathRules() { return pathRules; }
    public void setPathRules(List<PathRuleConfig> pathRules) { this.pathRules = pathRules; }

    public List<String> deniedCommands() { return deniedCommands; }
    public void setDeniedCommands(List<String> deniedCommands) { this.deniedCommands = deniedCommands; }

    /**
     * A glob-pattern path permission rule.
     */
    public static class PathRuleConfig {
        private String pattern;
        private boolean allow = true;

        public PathRuleConfig() {}

        public PathRuleConfig(String pattern, boolean allow) {
            this.pattern = pattern;
            this.allow = allow;
        }

        public String pattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }

        public boolean allow() { return allow; }
        public void setAllow(boolean allow) { this.allow = allow; }
    }
}
