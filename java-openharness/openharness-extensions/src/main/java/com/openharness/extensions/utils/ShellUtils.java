package com.openharness.extensions.utils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell and subprocess utilities.
 * Java equivalent of Python utils/shell.py.
 */
public final class ShellUtils {

    private ShellUtils() {}

    public static Process createShellProcess(String command, Path cwd, Map<String, String> env)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        if (env != null) {
            pb.environment().putAll(env);
        }
        pb.redirectErrorStream(true);
        return pb.start();
    }

    public static String readWithTimeout(Process p, Duration timeout)
            throws IOException, InterruptedException {
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            return "";
        }
        return new String(p.getInputStream().readAllBytes());
    }

    public static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
