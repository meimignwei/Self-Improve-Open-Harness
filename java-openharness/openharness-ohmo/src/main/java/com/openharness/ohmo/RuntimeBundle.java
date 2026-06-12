package com.openharness.ohmo;

import java.nio.file.Path;

/**
 * Per-session runtime bundle holding session-local state.
 * Java equivalent of Python ohmo/gateway/runtime.py RuntimeBundle.
 */
public class RuntimeBundle {

    private final String sessionKey;
    private final Path cwd;
    private final String providerProfile;
    private final Path workspaceRoot;
    private String systemPrompt;

    public RuntimeBundle(String sessionKey, Path cwd, String providerProfile, Path workspaceRoot) {
        this.sessionKey = sessionKey;
        this.cwd = cwd;
        this.providerProfile = providerProfile;
        this.workspaceRoot = workspaceRoot;
    }

    public void initialize(String userPrompt) {
        OhmoSystemPromptBuilder builder = new OhmoSystemPromptBuilder();
        this.systemPrompt = builder.build(cwd, workspaceRoot);
    }

    public String sessionKey() { return sessionKey; }
    public Path cwd() { return cwd; }
    public String providerProfile() { return providerProfile; }
    public Path workspaceRoot() { return workspaceRoot; }
    public String systemPrompt() { return systemPrompt; }

    public void setSystemPrompt(String prompt) { this.systemPrompt = prompt; }
}
