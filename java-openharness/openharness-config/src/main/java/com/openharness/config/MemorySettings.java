package com.openharness.config;

/**
 * Memory system configuration.
 * Java equivalent of Python's MemorySettings Pydantic model.
 */
public class MemorySettings {

    private boolean enabled = true;
    private int maxFiles = 5;
    private int maxEntrypointLines = 200;
    private int maxEntrypointBytes = 25_000;
    private Integer contextWindowTokens = null;
    private Integer autoCompactThresholdTokens = null;
    private boolean autoExtractEnabled = false;
    private int autoExtractMaxRecords = 3;
    private boolean sessionMemoryEnabled = true;
    private boolean autoDreamEnabled = false;
    private double autoDreamMinHours = 24.0;
    private int autoDreamMinSessions = 5;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int maxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }

    public int maxEntrypointLines() { return maxEntrypointLines; }
    public void setMaxEntrypointLines(int maxEntrypointLines) { this.maxEntrypointLines = maxEntrypointLines; }

    public int maxEntrypointBytes() { return maxEntrypointBytes; }
    public void setMaxEntrypointBytes(int maxEntrypointBytes) { this.maxEntrypointBytes = maxEntrypointBytes; }

    public Integer contextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(Integer contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }

    public Integer autoCompactThresholdTokens() { return autoCompactThresholdTokens; }
    public void setAutoCompactThresholdTokens(Integer autoCompactThresholdTokens) { this.autoCompactThresholdTokens = autoCompactThresholdTokens; }

    public boolean autoExtractEnabled() { return autoExtractEnabled; }
    public void setAutoExtractEnabled(boolean autoExtractEnabled) { this.autoExtractEnabled = autoExtractEnabled; }

    public int autoExtractMaxRecords() { return autoExtractMaxRecords; }
    public void setAutoExtractMaxRecords(int autoExtractMaxRecords) { this.autoExtractMaxRecords = autoExtractMaxRecords; }

    public boolean sessionMemoryEnabled() { return sessionMemoryEnabled; }
    public void setSessionMemoryEnabled(boolean sessionMemoryEnabled) { this.sessionMemoryEnabled = sessionMemoryEnabled; }

    public boolean autoDreamEnabled() { return autoDreamEnabled; }
    public void setAutoDreamEnabled(boolean autoDreamEnabled) { this.autoDreamEnabled = autoDreamEnabled; }

    public double autoDreamMinHours() { return autoDreamMinHours; }
    public void setAutoDreamMinHours(double autoDreamMinHours) { this.autoDreamMinHours = autoDreamMinHours; }

    public int autoDreamMinSessions() { return autoDreamMinSessions; }
    public void setAutoDreamMinSessions(int autoDreamMinSessions) { this.autoDreamMinSessions = autoDreamMinSessions; }
}
