package com.openharness.ohmo;

import com.openharness.common.AgentRuntime;
import com.openharness.common.UsageSnapshot;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session runtime bundle holding session-local state, engine reference, and tool metadata.
 * Java equivalent of Python ohmo/gateway/runtime.py RuntimeBundle.
 */
public class RuntimeBundle {

    private final String sessionKey;
    private final Path cwd;
    private final String providerProfile;
    private final Path workspaceRoot;
    private String systemPrompt;
    private String sessionId;
    private String model;
    private int maxTurns;
    private boolean enforceMaxTurns;
    private AgentRuntime engine;
    private Object toolRegistry;
    private List<Object> messages;
    private Map<String, Object> toolMetadata;
    private UsageSnapshot totalUsage;
    private final Map<String, Object> appState;

    public RuntimeBundle(String sessionKey, Path cwd, String providerProfile, Path workspaceRoot) {
        this.sessionKey = sessionKey;
        this.cwd = cwd;
        this.providerProfile = providerProfile;
        this.workspaceRoot = workspaceRoot;
        this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.messages = new ArrayList<>();
        this.toolMetadata = new ConcurrentHashMap<>();
        this.appState = new ConcurrentHashMap<>();
        this.totalUsage = new UsageSnapshot(0, 0);
    }

    public void initialize(String userPrompt) {
        OhmoSystemPromptBuilder builder = new OhmoSystemPromptBuilder();
        this.systemPrompt = builder.build(cwd, workspaceRoot);
    }

    // ------------------------------------------------------------------
    // Getters / Setters
    // ------------------------------------------------------------------

    public String sessionKey() { return sessionKey; }
    public Path cwd() { return cwd; }
    public String providerProfile() { return providerProfile; }
    public Path workspaceRoot() { return workspaceRoot; }
    public String systemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String prompt) { this.systemPrompt = prompt; }

    public String sessionId() { return sessionId; }
    public void setSessionId(String id) { this.sessionId = id; }

    public String model() { return model; }
    public void setModel(String m) { this.model = m; }

    public int maxTurns() { return maxTurns; }
    public void setMaxTurns(int mt) { this.maxTurns = mt; }

    public boolean enforceMaxTurns() { return enforceMaxTurns; }
    public void setEnforceMaxTurns(boolean e) { this.enforceMaxTurns = e; }

    public AgentRuntime engine() { return engine; }
    public void setEngine(AgentRuntime e) { this.engine = e; }

    public Object toolRegistry() { return toolRegistry; }
    public void setToolRegistry(Object tr) { this.toolRegistry = tr; }

    public List<Object> messages() { return messages; }
    public void setMessages(List<Object> msgs) { this.messages = msgs; }

    public Map<String, Object> toolMetadata() { return toolMetadata; }
    public void setToolMetadata(Map<String, Object> tm) { this.toolMetadata = tm; }

    public UsageSnapshot totalUsage() { return totalUsage; }
    public void setTotalUsage(UsageSnapshot u) { this.totalUsage = u; }

    public Map<String, Object> appState() { return appState; }

    /**
     * Minimal settings snapshot matching Python bundle.current_settings().
     */
    public SettingsSnapshot currentSettings() {
        return new SettingsSnapshot(systemPrompt, model, maxTurns, enforceMaxTurns);
    }

    public record SettingsSnapshot(String systemPrompt, String model, int maxTurns, boolean enforceMaxTurns) {}
}
