package com.openharness.extensions.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Platform detection and capability inspection.
 * Java equivalent of Python platforms.py.
 */
public record PlatformCapabilities(
        Platform platform,
        boolean posixShell,
        boolean tmuxSupport,
        boolean swarmMailbox,
        boolean dockerSandbox,
        boolean bwrapAvailable,
        boolean sandboxExec,
        String arch
) {

    public enum Platform { MACOS, LINUX, WSL, WINDOWS }

    public static PlatformCapabilities detect() {
        String os = System.getProperty("os.name").toLowerCase();
        Platform p = os.contains("linux")
                ? (isWsl() ? Platform.WSL : Platform.LINUX)
                : os.contains("mac") ? Platform.MACOS : Platform.WINDOWS;

        return new PlatformCapabilities(p,
                p != Platform.WINDOWS,
                commandExists("tmux"),
                p != Platform.WINDOWS,
                commandExists("docker"),
                commandExists("bwrap"),
                commandExists("sandbox-exec"),
                System.getProperty("os.arch"));
    }

    private static boolean isWsl() {
        try {
            return Files.readString(Path.of("/proc/version")).contains("microsoft");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
