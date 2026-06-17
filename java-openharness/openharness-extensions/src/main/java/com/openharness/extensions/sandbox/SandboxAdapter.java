package com.openharness.extensions.sandbox;

import com.openharness.config.SandboxSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Facade for sandbox operations. Delegates to {@link SrtSandbox} for SRT
 * runtime and {@link SandboxManager} for orchestration.
 *
 * Java equivalent of Python sandbox/adapter.py.
 */
public class SandboxAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SandboxAdapter.class);

    private final SrtSandbox srt;
    private final SandboxManager manager;

    public SandboxAdapter() {
        this.srt = new SrtSandbox();
        this.manager = new SandboxManager();
    }

    public SandboxAdapter(SandboxSettings defaultSettings) {
        this.srt = new SrtSandbox();
        this.manager = new SandboxManager(defaultSettings);
    }

    // ------------------------------------------------------------------
    // Availability
    // ------------------------------------------------------------------

    public SandboxAvailability checkAvailability() {
        boolean srtAvailable = srt.isAvailable();
        String engine = srt.sandboxEngine();

        if (!srtAvailable) {
            return new SandboxAvailability(false, false,
                    "srt CLI not found — install from https://github.com/anthropics/srt", null);
        }
        return new SandboxAvailability(true, engine != null,
                null, "srt", engine);
    }

    // ------------------------------------------------------------------
    // Config generation (delegates to SrtSandbox)
    // ------------------------------------------------------------------

    public Path buildRuntimeConfig(SandboxSettings settings) throws IOException {
        return srt.writeConfigFile(settings);
    }

    // ------------------------------------------------------------------
    // Command wrapping (delegates to SrtSandbox)
    // ------------------------------------------------------------------

    public String[] wrapCommand(String command, Path configFile) {
        return srt.wrapCommand(command, configFile).toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // Execution (delegates to SandboxManager)
    // ------------------------------------------------------------------

    /**
     * Execute a command through the sandbox.
     */
    public SandboxManager.SandboxExecutionResult execute(String command, String toolName, Path workingDir) {
        return manager.execute(command, toolName, workingDir);
    }

    public SandboxManager.SandboxExecutionResult execute(String command, Path workingDir) {
        return manager.execute(command, workingDir);
    }

    // ------------------------------------------------------------------
    // Manager access
    // ------------------------------------------------------------------

    public SandboxManager manager() {
        return manager;
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    public record SandboxAvailability(
            boolean enabled,
            boolean available,
            String reason,
            String command,
            String engine
    ) {
        public SandboxAvailability(boolean enabled, boolean available, String reason, String command) {
            this(enabled, available, reason, command, null);
        }
    }
}
