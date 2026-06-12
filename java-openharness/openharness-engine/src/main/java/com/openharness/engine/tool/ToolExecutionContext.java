package com.openharness.engine.tool;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared execution context for tool invocations.
 * Java equivalent of Python's ToolExecutionContext dataclass.
 */
public class ToolExecutionContext {

    private final Path cwd;
    private final Map<String, Object> metadata;

    public ToolExecutionContext(Path cwd) {
        this.cwd = cwd;
        this.metadata = new HashMap<>();
    }

    public ToolExecutionContext(Path cwd, Map<String, Object> metadata) {
        this.cwd = cwd;
        this.metadata = new HashMap<>(metadata);
    }

    public Path cwd() { return cwd; }
    public Map<String, Object> metadata() { return metadata; }
}
