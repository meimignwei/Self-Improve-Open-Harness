package io.agentscope.harness;

import io.agentscope.core.CompactionConfig;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class HarnessAgent {

    private final String name;
    private final Model model;
    private final Path workspace;
    private final Toolkit toolkit;
    private final CompactionConfig compaction;
    private final List<MiddlewareBase> middleware;
    private final boolean planModeEnabled;

    private HarnessAgent(Builder builder) {
        this.name = builder.name;
        this.model = builder.model;
        this.workspace = builder.workspace;
        this.toolkit = builder.toolkit;
        this.compaction = builder.compaction;
        this.middleware = List.copyOf(builder.middleware);
        this.planModeEnabled = builder.planModeEnabled;
    }

    public String getName() { return name; }
    public Model getModel() { return model; }
    public Path getWorkspace() { return workspace; }
    public Toolkit getToolkit() { return toolkit; }
    public CompactionConfig getCompaction() { return compaction; }
    public List<MiddlewareBase> getMiddleware() { return middleware; }
    public boolean isPlanModeEnabled() { return planModeEnabled; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private Model model;
        private Path workspace;
        private Toolkit toolkit;
        private CompactionConfig compaction;
        private final List<MiddlewareBase> middleware = new ArrayList<>();
        private boolean planModeEnabled;

        public Builder name(String name) { this.name = name; return this; }
        public Builder model(Model model) { this.model = model; return this; }
        public Builder workspace(Path workspace) { this.workspace = workspace; return this; }
        public Builder toolkit(Toolkit toolkit) { this.toolkit = toolkit; return this; }
        public Builder compaction(CompactionConfig compaction) { this.compaction = compaction; return this; }

        public Builder middleware(List<MiddlewareBase> m) { this.middleware.addAll(m); return this; }

        public Builder enablePlanMode() { this.planModeEnabled = true; return this; }

        public HarnessAgent build() {
            return new HarnessAgent(this);
        }
    }
}
