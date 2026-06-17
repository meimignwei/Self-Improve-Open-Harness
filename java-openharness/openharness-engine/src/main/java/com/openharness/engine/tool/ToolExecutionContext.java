package com.openharness.engine.tool;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared execution context for tool invocations.
 * Java equivalent of Python's ToolExecutionContext dataclass.
 * <p>
 * hookExecutor is stored as Object to avoid a dependency cycle
 * (openharness-engine cannot depend on openharness-extensions).
 * Callers that need HookExecutor cast it back to the concrete type.
 */
public class ToolExecutionContext {

    private final Path cwd;
    private final Map<String, Object> metadata;
    private final Object hookExecutor;

    public ToolExecutionContext(Path cwd) {
        this(cwd, new HashMap<>(), null);
    }

    public ToolExecutionContext(Path cwd, Map<String, Object> metadata) {
        this(cwd, metadata, null);
    }

    public ToolExecutionContext(Path cwd, Map<String, Object> metadata, Object hookExecutor) {
        this.cwd = cwd;
        this.metadata = new HashMap<>(metadata);
        this.hookExecutor = hookExecutor;
    }

    public Path cwd() { return cwd; }
    public Map<String, Object> metadata() { return metadata; }
    public Object hookExecutor() { return hookExecutor; }
}
