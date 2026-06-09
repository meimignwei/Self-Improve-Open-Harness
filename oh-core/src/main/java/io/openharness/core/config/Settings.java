package io.openharness.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局配置模型。支持从 JSON/YAML 反序列化，三层合并加载。
 */
public class Settings {

    // ── Model ──
    private String model;
    private int maxTurns;

    // ── Database ──
    private String dbUrl;
    private String dbUser;
    private String dbPassword;

    // ── API ──
    private String apiKey;
    private String apiBaseUrl;

    // ── Workspace ──
    private String workspaceDir;

    // ── Sandbox ──
    private Boolean sandboxEnabled;
    private String sandboxImage;

    // ── Logging ──
    private String logMode;
    private String logLevel;

    // ── Metrics ──
    private String metricsMode;
    private int metricsPort;

    // ── Compaction ──
    private int maxContextTokens;
    private Boolean autoCompaction;

    // ── Permission ──
    private Boolean permissionCheck;
    private List<String> allowedPaths;

    /** 硬编码默认值 */
    public static Settings defaults() {
        Settings s = new Settings();
        s.model = "claude-sonnet-4-6";
        s.maxTurns = 100;
        s.dbUrl = "jdbc:mysql://localhost:3306/oh";
        s.dbUser = "root";
        s.dbPassword = "";
        s.apiBaseUrl = "https://api.anthropic.com";
        s.workspaceDir = System.getProperty("user.dir");
        s.sandboxEnabled = true;
        s.sandboxImage = "oh-sandbox:latest";
        s.logMode = "local";
        s.logLevel = "INFO";
        s.metricsMode = "logging";
        s.metricsPort = 8080;
        s.maxContextTokens = 100_000;
        s.autoCompaction = true;
        s.permissionCheck = true;
        return s;
    }

    /**
     * 用 source 中的非空值覆盖当前实例的对应字段。
     * null 或空集合不覆盖，保持"上层覆盖下层"语义。
     */
    public void merge(Settings source) {
        if (source.model != null) this.model = source.model;
        if (source.maxTurns != 0) this.maxTurns = source.maxTurns;
        if (source.dbUrl != null) this.dbUrl = source.dbUrl;
        if (source.dbUser != null) this.dbUser = source.dbUser;
        if (source.dbPassword != null) this.dbPassword = source.dbPassword;
        if (source.apiKey != null) this.apiKey = source.apiKey;
        if (source.apiBaseUrl != null) this.apiBaseUrl = source.apiBaseUrl;
        if (source.workspaceDir != null) this.workspaceDir = source.workspaceDir;
        if (source.sandboxEnabled != null) this.sandboxEnabled = source.sandboxEnabled;
        if (source.sandboxImage != null) this.sandboxImage = source.sandboxImage;
        if (source.logMode != null) this.logMode = source.logMode;
        if (source.logLevel != null) this.logLevel = source.logLevel;
        if (source.metricsMode != null) this.metricsMode = source.metricsMode;
        if (source.metricsPort != 0) this.metricsPort = source.metricsPort;
        if (source.maxContextTokens != 0) this.maxContextTokens = source.maxContextTokens;
        if (source.autoCompaction != null) this.autoCompaction = source.autoCompaction;
        if (source.permissionCheck != null) this.permissionCheck = source.permissionCheck;
        if (source.allowedPaths != null && !source.allowedPaths.isEmpty()) this.allowedPaths = source.allowedPaths;
    }

    // ── Getters / Setters ──

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

    public String getDbUrl() { return dbUrl; }
    public void setDbUrl(String dbUrl) { this.dbUrl = dbUrl; }

    public String getDbUser() { return dbUser; }
    public void setDbUser(String dbUser) { this.dbUser = dbUser; }

    public String getDbPassword() { return dbPassword; }
    public void setDbPassword(String dbPassword) { this.dbPassword = dbPassword; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }

    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }

    public Boolean isSandboxEnabled() { return sandboxEnabled; }
    public void setSandboxEnabled(Boolean sandboxEnabled) { this.sandboxEnabled = sandboxEnabled; }

    public String getSandboxImage() { return sandboxImage; }
    public void setSandboxImage(String sandboxImage) { this.sandboxImage = sandboxImage; }

    public String getLogMode() { return logMode; }
    public void setLogMode(String logMode) { this.logMode = logMode; }

    public String getLogLevel() { return logLevel; }
    public void setLogLevel(String logLevel) { this.logLevel = logLevel; }

    public String getMetricsMode() { return metricsMode; }
    public void setMetricsMode(String metricsMode) { this.metricsMode = metricsMode; }

    public int getMetricsPort() { return metricsPort; }
    public void setMetricsPort(int metricsPort) { this.metricsPort = metricsPort; }

    public int getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }

    public Boolean isAutoCompaction() { return autoCompaction; }
    public void setAutoCompaction(Boolean autoCompaction) { this.autoCompaction = autoCompaction; }

    public Boolean isPermissionCheck() { return permissionCheck; }
    public void setPermissionCheck(Boolean permissionCheck) { this.permissionCheck = permissionCheck; }

    public List<String> getAllowedPaths() { return allowedPaths; }
    public void setAllowedPaths(List<String> allowedPaths) { this.allowedPaths = allowedPaths; }
}
