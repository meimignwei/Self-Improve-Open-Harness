package com.openharness.extensions.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.config.SandboxSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Full SRT (Sandbox Runtime) integration.
 *
 * SRT is a CLI tool that wraps commands in OS-level sandboxes:
 *   - Linux:   bubblewrap (bwrap)
 *   - macOS:   sandbox-exec (Apple Seatbelt)
 *
 * Java equivalent of Python sandbox/srt_runtime.py.
 */
public class SrtSandbox {

    private static final Logger logger = LoggerFactory.getLogger(SrtSandbox.class);
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    private final boolean available;
    private final String srtPath;
    private final String sandboxEngine; // "bwrap", "sandbox-exec", or null

    public SrtSandbox() {
        this.srtPath = findOnPath("srt");
        this.available = srtPath != null;
        this.sandboxEngine = detectEngine();
        logger.debug("SRT available={} path={} engine={}", available, srtPath, sandboxEngine);
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    public boolean isAvailable() {
        return available;
    }

    public String sandboxEngine() {
        return sandboxEngine;
    }

    private static String findOnPath(String name) {
        for (String dir : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(dir, name);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static String detectEngine() {
        // Linux: check for bubblewrap
        if (findOnPath("bwrap") != null) return "bwrap";

        // macOS: sandbox-exec is at a known path
        if (new java.io.File("/usr/bin/sandbox-exec").canExecute()) return "sandbox-exec";

        // Fallback: check uname
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) return "bwrap";
        if (os.contains("mac")) return "sandbox-exec";
        return null;
    }

    // ------------------------------------------------------------------
    // Config generation (SRT JSON schema)
    // ------------------------------------------------------------------

    /**
     * Build the full SRT JSON config matching the srt CLI schema.
     */
    public ObjectNode buildConfig(SandboxSettings settings) {
        ObjectNode root = MAPPER.createObjectNode();

        // Network section
        if (settings.network() != null) {
            ObjectNode network = root.putObject("network");
            SandboxSettings.SandboxNetworkSettings net = settings.network();

            if (net.allowedDomains() != null && !net.allowedDomains().isEmpty()) {
                ArrayNode allow = network.putArray("allow_outbound");
                net.allowedDomains().forEach(allow::add);
            }
            if (net.deniedDomains() != null && !net.deniedDomains().isEmpty()) {
                ArrayNode deny = network.putArray("deny_outbound");
                net.deniedDomains().forEach(deny::add);
            }
            // Default: deny all outbound if not explicitly allowed
            if ((net.allowedDomains() == null || net.allowedDomains().isEmpty())
                    && (net.deniedDomains() == null || net.deniedDomains().isEmpty())) {
                network.putArray("deny_outbound").add("*");
            }
        }

        // Filesystem section
        if (settings.filesystem() != null) {
            ObjectNode fs = root.putObject("filesystem");
            SandboxSettings.SandboxFilesystemSettings fss = settings.filesystem();

            if (fss.allowRead() != null && !fss.allowRead().isEmpty()) {
                ArrayNode ar = fs.putArray("allow_read");
                fss.allowRead().forEach(ar::add);
            }
            if (fss.denyRead() != null && !fss.denyRead().isEmpty()) {
                ArrayNode dr = fs.putArray("deny_read");
                fss.denyRead().forEach(dr::add);
            }
            if (fss.allowWrite() != null && !fss.allowWrite().isEmpty()) {
                ArrayNode aw = fs.putArray("allow_write");
                fss.allowWrite().forEach(aw::add);
            }
            if (fss.denyWrite() != null && !fss.denyWrite().isEmpty()) {
                ArrayNode dw = fs.putArray("deny_write");
                fss.denyWrite().forEach(dw::add);
            }
        }

        // Environment section — forward essential vars
        ObjectNode env = root.putObject("environment");
        for (String key : List.of("HOME", "USER", "PATH", "TMPDIR", "TEMP", "TMP",
                "LANG", "LC_ALL", "ANTHROPIC_API_KEY", "OPENAI_API_KEY")) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }

        // Sandbox engine hint
        if (sandboxEngine != null) {
            root.put("engine", sandboxEngine);
        }

        return root;
    }

    // ------------------------------------------------------------------
    // Config file management
    // ------------------------------------------------------------------

    /**
     * Write SRT config to a temp file. Caller should delete after execution.
     */
    public Path writeConfigFile(SandboxSettings settings) throws IOException {
        ObjectNode config = buildConfig(settings);
        Path tmp = Files.createTempFile("srt_config_", ".json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), config);
        logger.debug("SRT config written to {}", tmp);
        return tmp;
    }

    // ------------------------------------------------------------------
    // Command wrapping
    // ------------------------------------------------------------------

    /**
     * Build the command array for SRT execution.
     * Result: ["srt", "--settings", "/tmp/srt_xxx.json", "-c", "user command here"]
     */
    public List<String> wrapCommand(String command, Path configFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(srtPath != null ? srtPath : "srt");
        cmd.add("--settings");
        cmd.add(configFile.toAbsolutePath().toString());
        cmd.add("-c");
        cmd.add(command);
        return cmd;
    }

    /**
     * Build the command array with additional SRT flags.
     */
    public List<String> wrapCommand(String command, Path configFile, boolean allowNetwork) {
        List<String> cmd = new ArrayList<>();
        cmd.add(srtPath != null ? srtPath : "srt");
        cmd.add("--settings");
        cmd.add(configFile.toAbsolutePath().toString());
        if (allowNetwork) {
            cmd.add("--allow-network");
        }
        cmd.add("-c");
        cmd.add(command);
        return cmd;
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    /**
     * Execute a command inside the SRT sandbox.
     *
     * @param command     the shell command to run
     * @param settings    sandbox settings
     * @param workingDir  working directory (null = current)
     * @param timeoutMs   timeout in milliseconds
     * @return execution result
     */
    public SrtResult execute(String command, SandboxSettings settings,
                             Path workingDir, long timeoutMs) throws IOException {
        Path configFile = writeConfigFile(settings);
        try {
            List<String> cmd = wrapCommand(command, configFile);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workingDir != null) {
                pb.directory(workingDir.toFile());
            }
            pb.redirectErrorStream(false);

            long start = System.currentTimeMillis();
            Process p = pb.start();

            // Read stdout/stderr in parallel
            java.util.concurrent.CompletableFuture<String> stdoutFuture =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return new String(p.getInputStream().readAllBytes());
                        } catch (IOException e) {
                            return "";
                        }
                    });
            java.util.concurrent.CompletableFuture<String> stderrFuture =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return new String(p.getErrorStream().readAllBytes());
                        } catch (IOException e) {
                            return "";
                        }
                    });

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            String stdout = "";
            String stderr = "";
            try {
                stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            try {
                stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}

            if (!finished) {
                p.destroyForcibly();
                return new SrtResult(-1, stdout, stderr, elapsed, true, "timeout after " + timeoutMs + "ms");
            }

            int exitCode = p.exitValue();
            return new SrtResult(exitCode, stdout, stderr, elapsed, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SrtResult(-1, "", "interrupted", 0, false, "interrupted");
        } finally {
            try { Files.deleteIfExists(configFile); } catch (IOException ignored) {}
        }
    }

    /**
     * Execute with default timeout (60s).
     */
    public SrtResult execute(String command, SandboxSettings settings, Path workingDir) throws IOException {
        return execute(command, settings, workingDir, 60_000);
    }

    // ------------------------------------------------------------------
    // Command validation
    // ------------------------------------------------------------------

    /**
     * Check whether a command can be sandboxed.
     * SRT cannot sandbox commands that require raw device access, kernel modules, etc.
     */
    public static boolean isSandboxable(String command) {
        if (command == null || command.isBlank()) return false;
        String trimmed = command.trim();
        // Commands that typically need raw device access
        if (trimmed.startsWith("mount ") || trimmed.startsWith("umount ")) return false;
        if (trimmed.startsWith("insmod ") || trimmed.startsWith("rmmod ")) return false;
        if (trimmed.startsWith("iptables ") || trimmed.startsWith("nft ")) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    public record SrtResult(
            int exitCode,
            String stdout,
            String stderr,
            long elapsedMs,
            boolean timedOut,
            String error
    ) {
        public boolean success() {
            return exitCode == 0 && !timedOut && error == null;
        }

        public String combinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) sb.append(stdout);
            if (stderr != null && !stderr.isEmpty()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append("[stderr] ").append(stderr);
            }
            return sb.toString();
        }
    }
}
