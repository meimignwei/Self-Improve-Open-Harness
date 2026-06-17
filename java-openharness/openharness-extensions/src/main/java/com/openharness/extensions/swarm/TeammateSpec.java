package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Specifications for spawning a teammate agent.
 * Java equivalent of Python swarm/types.py TeammateSpawnConfig.
 */
@JsonDeserialize
public record TeammateSpec(
        @JsonProperty("name") String name,
        @JsonProperty("team") String team,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("parent_session_id") String parentSessionId,
        @JsonProperty("model") String model,
        @JsonProperty("command") String command,
        @JsonProperty("system_prompt") String systemPrompt,
        @JsonProperty("system_prompt_mode") String systemPromptMode,
        @JsonProperty("color") String color,
        @JsonProperty("color_override") String colorOverride,
        @JsonProperty("permissions") List<String> permissions,
        @JsonProperty("plan_mode_required") boolean planModeRequired,
        @JsonProperty("allow_permission_prompts") boolean allowPermissionPrompts,
        @JsonProperty("worktree_path") Path worktreePath,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("subscriptions") List<String> subscriptions,
        @JsonProperty("task_type") String taskType,
        @JsonProperty("env") Map<String, String> env,
        @JsonProperty("leader_mailbox_path") Path leaderMailboxPath) {

    public TeammateSpec {
        if (team == null) team = "";
        if (model == null) model = null;
        if (command == null) command = null;
        if (systemPrompt == null) systemPrompt = null;
        if (systemPromptMode == null) systemPromptMode = null;
        if (color == null) color = null;
        if (colorOverride == null) colorOverride = null;
        if (permissions == null) permissions = List.of();
        if (worktreePath == null) worktreePath = null;
        if (sessionId == null) sessionId = UUID.randomUUID().toString();
        if (subscriptions == null) subscriptions = List.of();
        if (taskType == null) taskType = "local_agent";
        if (env == null) env = Map.of();
        if (leaderMailboxPath == null) leaderMailboxPath = null;
    }

    public String agentType() {
        return name;
    }

    /**
     * Builder for convenient construction in Java code.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String team = "";
        private String prompt;
        private String cwd;
        private String parentSessionId;
        private String model;
        private String command;
        private String systemPrompt;
        private String systemPromptMode;
        private String color;
        private String colorOverride;
        private List<String> permissions = List.of();
        private boolean planModeRequired;
        private boolean allowPermissionPrompts;
        private Path worktreePath;
        private String sessionId;
        private List<String> subscriptions = List.of();
        private String taskType = "local_agent";
        private Map<String, String> env = Map.of();
        private Path leaderMailboxPath;

        public Builder name(String name) { this.name = name; return this; }
        public Builder team(String team) { this.team = team; return this; }
        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder cwd(String cwd) { this.cwd = cwd; return this; }
        public Builder parentSessionId(String id) { this.parentSessionId = id; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder command(String command) { this.command = command; return this; }
        public Builder systemPrompt(String sp) { this.systemPrompt = sp; return this; }
        public Builder systemPromptMode(String mode) { this.systemPromptMode = mode; return this; }
        public Builder color(String color) { this.color = color; return this; }
        public Builder colorOverride(String color) { this.colorOverride = color; return this; }
        public Builder permissions(List<String> p) { this.permissions = p; return this; }
        public Builder planModeRequired(boolean b) { this.planModeRequired = b; return this; }
        public Builder allowPermissionPrompts(boolean b) { this.allowPermissionPrompts = b; return this; }
        public Builder worktreePath(Path p) { this.worktreePath = p; return this; }
        public Builder sessionId(String id) { this.sessionId = id; return this; }
        public Builder subscriptions(List<String> s) { this.subscriptions = s; return this; }
        public Builder taskType(String t) { this.taskType = t; return this; }
        public Builder env(Map<String, String> e) { this.env = e; return this; }
        public Builder leaderMailboxPath(Path p) { this.leaderMailboxPath = p; return this; }

        public TeammateSpec build() {
            return new TeammateSpec(name, team, prompt, cwd, parentSessionId,
                    model, command, systemPrompt, systemPromptMode, color, colorOverride,
                    permissions, planModeRequired, allowPermissionPrompts,
                    worktreePath, sessionId, subscriptions, taskType, env, leaderMailboxPath);
        }
    }
}
