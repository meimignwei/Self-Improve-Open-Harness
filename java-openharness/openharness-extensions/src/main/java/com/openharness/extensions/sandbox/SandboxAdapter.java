package com.openharness.extensions.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.config.SandboxSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds sandbox runtime configuration and wraps commands for srt CLI.
 * Java equivalent of Python sandbox/adapter.py.
 */
public class SandboxAdapter {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    public SandboxAvailability checkAvailability() {
        boolean srtAvailable = checkCli("srt");
        boolean bwrapAvailable = checkCli("bwrap");
        boolean sandboxExecAvailable = checkCli("sandbox-exec");

        if (!srtAvailable) {
            return new SandboxAvailability(false, false,
                    "srt CLI not found", null);
        }
        return new SandboxAvailability(true,
                bwrapAvailable || sandboxExecAvailable,
                null, "srt");
    }

    public Path buildRuntimeConfig(SandboxSettings settings) throws IOException {
        var network = settings.network();
        var filesystem = settings.filesystem();
        Map<String, Object> config = Map.of(
                "network", Map.of(
                        "allow_outbound", network.allowedDomains(),
                        "deny_outbound", network.deniedDomains()),
                "filesystem", Map.of(
                        "allow_read", filesystem.allowRead(),
                        "allow_write", filesystem.allowWrite(),
                        "deny_read", filesystem.denyRead()));

        Path tmpFile = Files.createTempFile("srt_config_", ".json");
        MAPPER.writeValue(tmpFile.toFile(), config);
        return tmpFile;
    }

    public String[] wrapCommand(String command, Path configFile) {
        return new String[]{"srt", "--settings", configFile.toString(), "-c", command};
    }

    private boolean checkCli(String name) {
        try {
            Process p = new ProcessBuilder("which", name).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public record SandboxAvailability(boolean enabled, boolean available, String reason, String command) {}
}
