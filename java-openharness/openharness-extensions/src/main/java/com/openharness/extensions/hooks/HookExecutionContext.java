package com.openharness.extensions.hooks;

import com.openharness.common.AgentRuntime;
import com.openharness.extensions.sandbox.SandboxManager;

import java.nio.file.Path;

/**
 * Context passed into hook execution.
 * Java equivalent of Python's HookExecutionContext dataclass.
 */
public class HookExecutionContext {

    private final Path cwd;
    private AgentRuntime apiClient;
    private String defaultModel;
    private SandboxManager sandboxManager;

    public HookExecutionContext(Path cwd, AgentRuntime apiClient, String defaultModel) {
        this.cwd = cwd;
        this.apiClient = apiClient;
        this.defaultModel = defaultModel;
    }

    public Path cwd() { return cwd; }
    public AgentRuntime apiClient() { return apiClient; }
    public String defaultModel() { return defaultModel; }
    public SandboxManager sandboxManager() { return sandboxManager; }

    public void setApiClient(AgentRuntime apiClient) { this.apiClient = apiClient; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public void setSandboxManager(SandboxManager sandboxManager) { this.sandboxManager = sandboxManager; }
}
