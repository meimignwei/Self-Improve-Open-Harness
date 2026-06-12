package com.openharness.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Manages React/Ink frontend subprocess lifecycle.
 * Java equivalent of Python ui/react_launcher.py.
 */
public class ReactLauncher {

    private Process reactProcess;
    private final Path bundlePath;

    public ReactLauncher() {
        String home = System.getProperty("user.home");
        this.bundlePath = Path.of(home, ".openharness", "frontend", "dist", "server.js");
    }

    public ReactLauncher(Path bundlePath) {
        this.bundlePath = bundlePath;
    }

    public boolean isAvailable() {
        return commandExists("node") && Files.exists(bundlePath);
    }

    public void launch() throws IOException {
        if (reactProcess != null && reactProcess.isAlive()) {
            return;
        }

        var pb = new ProcessBuilder("node", bundlePath.toString());
        pb.environment().put("NODE_ENV", "production");
        pb.redirectErrorStream(true);
        reactProcess = pb.start();
    }

    public void shutdown() {
        if (reactProcess != null && reactProcess.isAlive()) {
            reactProcess.destroy();
            try {
                reactProcess.waitFor(3, TimeUnit.SECONDS);
                if (reactProcess.isAlive()) {
                    reactProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                reactProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return reactProcess != null && reactProcess.isAlive();
    }

    public Process process() {
        return reactProcess;
    }

    private static boolean commandExists(String cmd) {
        try {
            return Runtime.getRuntime().exec(new String[]{"which", cmd}).waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
