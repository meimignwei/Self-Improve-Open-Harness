package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.UUID;

/**
 * Configuration for spawning a teammate (any execution mode).
 * Java equivalent of Python swarm/types.py TeammateSpawnConfig.
 */
@JsonDeserialize
public record TeammateSpawnConfig(
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
        @JsonProperty("worktree_path") String worktreePath,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("subscriptions") List<String> subscriptions,
        @JsonProperty("task_type") String taskType) {

    public TeammateSpawnConfig {
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
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String team;
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
        private String worktreePath;
        private String sessionId;
        private List<String> subscriptions = List.of();
        private String taskType = "local_agent";

        public Builder name(String name) { this.name = name; return this; }
        public Builder team(String team) { this.team = team; return this; }
        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder cwd(String cwd) { this.cwd = cwd; return this; }
        public Builder parentSessionId(String parentSessionId) { this.parentSessionId = parentSessionId; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder command(String command) { this.command = command; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder systemPromptMode(String systemPromptMode) { this.systemPromptMode = systemPromptMode; return this; }
        public Builder color(String color) { this.color = color; return this; }
        public Builder colorOverride(String colorOverride) { this.colorOverride = colorOverride; return this; }
        public Builder permissions(List<String> permissions) { this.permissions = permissions; return this; }
        public Builder planModeRequired(boolean planModeRequired) { this.planModeRequired = planModeRequired; return this; }
        public Builder allowPermissionPrompts(boolean allowPermissionPrompts) { this.allowPermissionPrompts = allowPermissionPrompts; return this; }
        public Builder worktreePath(String worktreePath) { this.worktreePath = worktreePath; return this; }
        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder subscriptions(List<String> subscriptions) { this.subscriptions = subscriptions; return this; }
        public Builder taskType(String taskType) { this.taskType = taskType; return this; }

        public TeammateSpawnConfig build() {
            return new TeammateSpawnConfig(name, team, prompt, cwd, parentSessionId,
                    model, command, systemPrompt, systemPromptMode, color, colorOverride,
                    permissions, planModeRequired, allowPermissionPrompts,
                    worktreePath, sessionId, subscriptions, taskType);
        }
    }
}