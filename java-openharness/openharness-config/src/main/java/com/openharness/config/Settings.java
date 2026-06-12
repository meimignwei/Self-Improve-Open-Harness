package com.openharness.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main settings model for OpenHarness.
 * Java equivalent of Python's Settings Pydantic model (1105 lines).
 * <p>
 * Resolution precedence (highest first):
 * 1. CLI arguments
 * 2. Environment variables
 * 3. Config file (~/.openharness/settings.json)
 * 4. Defaults
 */
public class Settings {

    // ── API configuration ──
    private String apiKey = "";
    private String model = "claude-sonnet-4-6";
    private int maxTokens = 16384;
    private String baseUrl = null;
    private double timeout = 30.0;
    private Integer contextWindowTokens = null;
    private Integer autoCompactThresholdTokens = null;
    private String apiFormat = "anthropic";
    private String provider = "";
    private String activeProfile = "claude-api";
    private Map<String, ProviderProfile> profiles = new LinkedHashMap<>(defaultProviderProfiles());
    private int maxTurns = 200;

    // ── Behavior ──
    private String systemPrompt = null;
    private PermissionSettings permission = new PermissionSettings();
    private MemorySettings memory = new MemorySettings();
    private SandboxSettings sandbox = new SandboxSettings();
    private WebSettings web = new WebSettings();
    private Map<String, Boolean> enabledPlugins = new LinkedHashMap<>();
    private boolean allowProjectPlugins = false;
    private boolean allowProjectSkills = true;
    private List<String> projectSkillDirs = List.of(
            ".openharness/skills", ".agents/skills", ".claude/skills");

    // ── UI ──
    private String theme = "default";
    private String outputStyle = "default";
    private boolean vimMode = false;
    private boolean voiceMode = false;
    private boolean fastMode = false;
    private String effort = "medium";
    private int passes = 1;
    private boolean verbose = false;

    // ── Methods ──

    /**
     * Merges user-defined profiles over the built-in catalog.
     */
    public Map<String, ProviderProfile> mergedProfiles() {
        Map<String, ProviderProfile> merged = new LinkedHashMap<>(defaultProviderProfiles());
        if (profiles != null) {
            merged.putAll(profiles);
        }
        return merged;
    }

    /**
     * Atomically persists settings to the standard config file path.
     */
    public void save() {
        AtomicFileWriter.writeJson(Paths.configFilePath(), this);
    }

    /**
     * Loads settings from the standard config file path, or returns defaults.
     */
    public static Settings load() {
        Settings loaded = AtomicFileWriter.readJson(Paths.configFilePath(), Settings.class);
        return loaded != null ? loaded : new Settings();
    }

    /**
     * Loads settings from a specific path.
     */
    public static Settings load(Path path) {
        Settings loaded = AtomicFileWriter.readJson(path, Settings.class);
        return loaded != null ? loaded : new Settings();
    }

    // ── Built-in provider catalog ──

    public static Map<String, ProviderProfile> defaultProviderProfiles() {
        return new LinkedHashMap<>(Map.of(
                "claude-api", new ProviderProfile("Anthropic Claude", "anthropic",
                        "anthropic", "claude_subscription", "claude-sonnet-4-6"),
                "openai", new ProviderProfile("OpenAI", "openai",
                        "openai", "openai_api_key", "gpt-4o"),
                "dashscope", new ProviderProfile("DashScope", "dashscope",
                        "openai", "dashscope_api_key", "qwen-plus"),
                "deepseek", new ProviderProfile("DeepSeek", "deepseek",
                        "openai", "deepseek_api_key", "deepseek-chat"),
                "gemini", new ProviderProfile("Gemini", "gemini",
                        "openai", "gemini_api_key", "gemini-pro"),
                "groq", new ProviderProfile("Groq", "groq",
                        "openai", "groq_api_key", "llama-3.1-70b-versatile"),
                "ollama", new ProviderProfile("Ollama", "ollama",
                        "openai", "none", "llama3.1"),
                "moonshot", new ProviderProfile("Moonshot", "moonshot",
                        "anthropic", "moonshot_api_key", "moonshot-v1-8k"),
                "minimax", new ProviderProfile("MiniMax", "minimax",
                        "anthropic", "minimax_api_key", "abab6.5s-chat"),
                "xai_grok", new ProviderProfile("xAI Grok", "xai_grok",
                        "openai", "xai_api_key", "grok-2")
        ));
    }

    // ── Getters and setters ──

    public String apiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String model() { return model; }
    public void setModel(String model) { this.model = model; }

    public int maxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public String baseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public double timeout() { return timeout; }
    public void setTimeout(double timeout) { this.timeout = timeout; }

    public Integer contextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(Integer contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }

    public Integer autoCompactThresholdTokens() { return autoCompactThresholdTokens; }
    public void setAutoCompactThresholdTokens(Integer autoCompactThresholdTokens) { this.autoCompactThresholdTokens = autoCompactThresholdTokens; }

    public String apiFormat() { return apiFormat; }
    public void setApiFormat(String apiFormat) { this.apiFormat = apiFormat; }

    public String provider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String activeProfile() { return activeProfile; }
    public void setActiveProfile(String activeProfile) { this.activeProfile = activeProfile; }

    public Map<String, ProviderProfile> profiles() { return profiles; }
    public void setProfiles(Map<String, ProviderProfile> profiles) { this.profiles = profiles; }

    public int maxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

    public String systemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public PermissionSettings permission() { return permission; }
    public void setPermission(PermissionSettings permission) { this.permission = permission; }

    public MemorySettings memory() { return memory; }
    public void setMemory(MemorySettings memory) { this.memory = memory; }

    public SandboxSettings sandbox() { return sandbox; }
    public void setSandbox(SandboxSettings sandbox) { this.sandbox = sandbox; }

    public WebSettings web() { return web; }
    public void setWeb(WebSettings web) { this.web = web; }

    public Map<String, Boolean> enabledPlugins() { return enabledPlugins; }
    public void setEnabledPlugins(Map<String, Boolean> enabledPlugins) { this.enabledPlugins = enabledPlugins; }

    public boolean allowProjectPlugins() { return allowProjectPlugins; }
    public void setAllowProjectPlugins(boolean allowProjectPlugins) { this.allowProjectPlugins = allowProjectPlugins; }

    public boolean allowProjectSkills() { return allowProjectSkills; }
    public void setAllowProjectSkills(boolean allowProjectSkills) { this.allowProjectSkills = allowProjectSkills; }

    public List<String> projectSkillDirs() { return projectSkillDirs; }
    public void setProjectSkillDirs(List<String> projectSkillDirs) { this.projectSkillDirs = projectSkillDirs; }

    public String theme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public String outputStyle() { return outputStyle; }
    public void setOutputStyle(String outputStyle) { this.outputStyle = outputStyle; }

    public boolean vimMode() { return vimMode; }
    public void setVimMode(boolean vimMode) { this.vimMode = vimMode; }

    public boolean voiceMode() { return voiceMode; }
    public void setVoiceMode(boolean voiceMode) { this.voiceMode = voiceMode; }

    public boolean fastMode() { return fastMode; }
    public void setFastMode(boolean fastMode) { this.fastMode = fastMode; }

    public String effort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }

    public int passes() { return passes; }
    public void setPasses(int passes) { this.passes = passes; }

    public boolean verbose() { return verbose; }
    public void setVerbose(boolean verbose) { this.verbose = verbose; }
}
