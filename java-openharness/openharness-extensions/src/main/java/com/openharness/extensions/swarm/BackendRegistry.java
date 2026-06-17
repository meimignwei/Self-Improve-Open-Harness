package com.openharness.extensions.swarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry that maps backend type names to TeammateBackend instances.
 * Detection priority pipeline mirrors registry.py / registry.ts.
 *
 * Java equivalent of Python swarm/registry.py BackendRegistry.
 */
public final class BackendRegistry {

    private static final Logger logger = LoggerFactory.getLogger(BackendRegistry.class);

    private static volatile BackendRegistry instance;

    private final Map<String, TeammateBackend> backends = new LinkedHashMap<>();
    private String detected;
    private BackendDetectionResult detectionResult;
    private boolean inProcessFallbackActive;

    public static synchronized BackendRegistry getInstance() {
        if (instance == null) {
            instance = new BackendRegistry();
        }
        return instance;
    }

    static synchronized void resetInstance() {
        instance = null;
    }

    private BackendRegistry() {
        registerDefaults();
    }

    public void register(String name, TeammateBackend backend) {
        backends.put(name, backend);
        logger.debug("Registered backend: {}", name);
    }

    public TeammateBackend get(String name) {
        return backends.get(name);
    }

    public Map<String, TeammateBackend> all() {
        return Map.copyOf(backends);
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /**
     * Detect and cache the most capable available backend.
     * Priority: 1) in_process fallback, 2) tmux, 3) subprocess.
     */
    public String detectBackend() {
        if (detected != null) {
            logger.debug("[BackendRegistry] Using cached backend detection: {}", detected);
            return detected;
        }

        logger.debug("[BackendRegistry] Starting backend detection...");

        if (inProcessFallbackActive) {
            logger.debug("[BackendRegistry] in_process fallback active — selecting in_process");
            detected = "in_process";
            detectionResult = new BackendDetectionResult("in_process", true);
            return detected;
        }

        boolean insideTmux = SpawnUtils.isInsideTmux();
        if (insideTmux && backends.containsKey("tmux")) {
            logger.debug("[BackendRegistry] Selected: tmux (running inside tmux session)");
            detected = "tmux";
            detectionResult = new BackendDetectionResult("tmux", true);
            return detected;
        }

        logger.debug("[BackendRegistry] Selected: subprocess (default fallback)");
        detected = "subprocess";
        detectionResult = new BackendDetectionResult("subprocess", false);
        return detected;
    }

    public BackendDetectionResult detectPaneBackend() {
        boolean inTmux = SpawnUtils.isInsideTmux();
        boolean inIterm2 = System.getenv("ITERM_SESSION_ID") != null;

        if (inTmux) {
            return new BackendDetectionResult("tmux", true);
        }

        if (inIterm2) {
            boolean it2Available = findOnPath("it2") != null;
            if (it2Available) {
                return new BackendDetectionResult("iterm2", true);
            }
            if (SpawnUtils.isTmuxAvailable()) {
                return new BackendDetectionResult("tmux", false, true);
            }
            throw new RuntimeException(
                    "iTerm2 detected but it2 CLI not installed.\nInstall it2 with: pip install it2");
        }

        if (SpawnUtils.isTmuxAvailable()) {
            return new BackendDetectionResult("tmux", false);
        }

        throw new RuntimeException(getTmuxInstallInstructions());
    }

    public TeammateBackend getExecutor(String backend) {
        String resolved = backend != null ? backend : detectBackend();
        TeammateBackend executor = backends.get(resolved);
        if (executor == null) {
            throw new IllegalStateException(
                    "Backend '" + resolved + "' is not registered. Available: " + backends.keySet());
        }
        return executor;
    }

    public TeammateBackend getDefault() {
        return getExecutor(detectBackend());
    }

    public String getPreferredBackend(Map<String, Object> config) {
        String mode;
        if (config != null && config.containsKey("teammate_mode")) {
            mode = (String) config.get("teammate_mode");
        } else {
            mode = System.getenv().getOrDefault("OPENHARNESS_TEAMMATE_MODE", "auto");
        }
        switch (mode) {
            case "in_process": return "in_process";
            case "tmux": return "tmux";
            default: return detectBackend();
        }
    }

    public void markInProcessFallback() {
        this.inProcessFallbackActive = true;
        this.detected = null;
        this.detectionResult = null;
    }

    public BackendDetectionResult getCachedDetectionResult() {
        return detectionResult;
    }

    public List<String> availableBackends() {
        List<String> result = new ArrayList<>(backends.keySet());
        result.sort(String::compareTo);
        return result;
    }

    public Map<String, Object> healthCheck() {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        int availableCount = 0;

        for (var entry : backends.entrySet()) {
            boolean available = entry.getValue().isAvailable();
            results.put(entry.getKey(), Map.of(
                    "available", available,
                    "type", entry.getValue().type()));
            if (available) availableCount++;
        }

        return Map.of("backends", results, "total_count", availableCount);
    }

    public void setDefault(String name) {
        if (backends.containsKey(name)) {
            this.detected = name;
        }
    }

    public void reset() {
        this.detected = null;
        this.detectionResult = null;
        this.inProcessFallbackActive = false;
        this.backends.clear();
        registerDefaults();
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private void registerDefaults() {
        backends.put("subprocess", new SubprocessBackend());
        backends.put("in_process", new InProcessBackend(null));
        if (SpawnUtils.isTmuxAvailable()) {
            backends.put("tmux", new TmuxBackend());
        }
    }

    private static String getTmuxInstallInstructions() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "To use agent swarms, install tmux:\n  brew install tmux\n" +
                   "Then start a tmux session with: tmux new-session -s claude";
        } else if (os.contains("linux")) {
            return "To use agent swarms, install tmux:\n  sudo apt install tmux\n" +
                   "Then start a tmux session with: tmux new-session -s claude";
        }
        return "To use agent swarms, install tmux using your system's package manager.";
    }

    private static String findOnPath(String name) {
        for (String dir : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            java.io.File file = new java.io.File(dir, name);
            if (file.exists() && file.canExecute()) return file.getAbsolutePath();
        }
        return null;
    }
}
