package com.openharness.observability;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.DiskSpaceMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized Micrometer metrics registry.
 * Pre-defines all metric instruments for Agent Loop, LLM API, Tool execution,
 * Channel/gateway, Memory, Swarm, and JVM system metrics.
 */
public class OpenHarnessMeters implements AutoCloseable {

    private final MeterRegistry registry;

    // --- Agent Loop ---
    private final Counter loopIterations;
    private final Timer loopDuration;
    private final AtomicInteger activeLoops = new AtomicInteger(0);
    private final DistributionSummary toolsPerTurn;
    private final Counter maxTurnsExceeded;
    private final Counter compactionTriggered;

    // --- LLM API ---
    private final Counter apiCalls;
    private final Timer apiLatency;
    private final Timer ttft;
    private final Counter tokensInput;
    private final Counter tokensOutput;
    private final Counter apiCost;
    private final Counter apiRetries;
    private final Counter apiRateLimits;
    private final Counter apiStreamChunks;
    private final Counter apiErrors;

    // --- Tool Execution ---
    private final Counter toolInvocations;
    private final Timer toolDuration;
    private final DistributionSummary toolOutputBytes;
    private final Counter permissionChecks;

    // --- Channel ---
    private final Counter channelMessagesInbound;
    private final Counter channelMessagesOutbound;
    private final Timer channelMessageLatency;
    private final AtomicInteger channelSessionsActive = new AtomicInteger(0);
    private final Counter channelErrors;

    // --- Memory ---
    private final AtomicInteger memoryEntriesTotal = new AtomicInteger(0);
    private final Timer memorySearchDuration;
    private final Counter memoryOperations;

    public OpenHarnessMeters() {
        this(new SimpleMeterRegistry());
    }

    public OpenHarnessMeters(MeterRegistry registry) {
        this.registry = registry;

        // Agent Loop metrics
        this.loopIterations = Counter.builder("agent.loop.iterations")
                .description("Total agent loop iterations")
                .register(registry);
        this.loopDuration = Timer.builder("agent.loop.duration")
                .description("Agent loop iteration duration")
                .register(registry);
        Gauge.builder("agent.loop.active", activeLoops, AtomicInteger::get)
                .description("Currently active agent loops")
                .register(registry);
        this.toolsPerTurn = DistributionSummary.builder("agent.loop.tools_per_turn")
                .description("Number of tools called per turn")
                .register(registry);
        this.maxTurnsExceeded = Counter.builder("agent.loop.max_turns_exceeded")
                .description("Number of times max turns was exceeded")
                .register(registry);
        this.compactionTriggered = Counter.builder("agent.loop.compaction_triggered")
                .description("Compaction trigger count")
                .register(registry);

        // LLM API metrics
        this.apiCalls = Counter.builder("llm.api.calls")
                .description("Total LLM API calls")
                .register(registry);
        this.apiLatency = Timer.builder("llm.api.latency")
                .description("LLM API call latency")
                .register(registry);
        this.ttft = Timer.builder("llm.api.ttft")
                .description("Time to first token")
                .register(registry);
        this.tokensInput = Counter.builder("llm.tokens.input")
                .description("Input token count")
                .register(registry);
        this.tokensOutput = Counter.builder("llm.tokens.output")
                .description("Output token count")
                .register(registry);
        this.apiCost = Counter.builder("llm.api.cost.usd")
                .description("API cost in USD")
                .register(registry);
        this.apiRetries = Counter.builder("llm.api.retries")
                .description("API retry count")
                .register(registry);
        this.apiRateLimits = Counter.builder("llm.api.rate_limits")
                .description("Rate limit hit count")
                .register(registry);
        this.apiStreamChunks = Counter.builder("llm.api.stream.chunks")
                .description("Stream chunk count")
                .register(registry);
        this.apiErrors = Counter.builder("llm.api.errors")
                .description("API error count")
                .register(registry);

        // Tool Execution metrics
        this.toolInvocations = Counter.builder("tool.invocations")
                .description("Tool invocation count")
                .register(registry);
        this.toolDuration = Timer.builder("tool.execution.duration")
                .description("Tool execution duration")
                .register(registry);
        this.toolOutputBytes = DistributionSummary.builder("tool.output.bytes")
                .description("Tool output size in bytes")
                .register(registry);
        this.permissionChecks = Counter.builder("tool.permission.checks")
                .description("Permission check count")
                .register(registry);

        // Channel metrics
        this.channelMessagesInbound = Counter.builder("channel.messages.inbound")
                .description("Inbound channel message count")
                .register(registry);
        this.channelMessagesOutbound = Counter.builder("channel.messages.outbound")
                .description("Outbound channel message count")
                .register(registry);
        this.channelMessageLatency = Timer.builder("channel.message.latency")
                .description("Channel message latency")
                .register(registry);
        Gauge.builder("channel.sessions.active", channelSessionsActive, AtomicInteger::get)
                .description("Active channel sessions")
                .register(registry);
        this.channelErrors = Counter.builder("channel.errors")
                .description("Channel error count")
                .register(registry);

        // Memory metrics
        Gauge.builder("memory.entries.total", memoryEntriesTotal, AtomicInteger::get)
                .description("Total memory entries")
                .register(registry);
        this.memorySearchDuration = Timer.builder("memory.search.duration")
                .description("Memory search duration")
                .register(registry);
        this.memoryOperations = Counter.builder("memory.operations")
                .description("Memory operation count")
                .register(registry);

        // JVM system metrics (auto-register)
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        File cwd = new File(".").getAbsoluteFile();
        new DiskSpaceMetrics(cwd).bindTo(registry);
    }

    public MeterRegistry registry() { return registry; }

    // --- Agent Loop accessors ---
    public Counter loopIterations() { return loopIterations; }
    public Timer loopDuration() { return loopDuration; }
    public AtomicInteger activeLoops() { return activeLoops; }
    public DistributionSummary toolsPerTurn() { return toolsPerTurn; }
    public Counter maxTurnsExceeded() { return maxTurnsExceeded; }
    public Counter compactionTriggered() { return compactionTriggered; }

    // --- LLM API accessors ---
    public Counter apiCalls() { return apiCalls; }
    public Timer apiLatency() { return apiLatency; }
    public Timer ttft() { return ttft; }
    public Counter tokensInput() { return tokensInput; }
    public Counter tokensOutput() { return tokensOutput; }
    public Counter apiCost() { return apiCost; }
    public Counter apiRetries() { return apiRetries; }
    public Counter apiRateLimits() { return apiRateLimits; }
    public Counter apiStreamChunks() { return apiStreamChunks; }
    public Counter apiErrors() { return apiErrors; }

    // --- Tool accessors ---
    public Counter toolInvocations() { return toolInvocations; }
    public Timer toolDuration() { return toolDuration; }
    public DistributionSummary toolOutputBytes() { return toolOutputBytes; }
    public Counter permissionChecks() { return permissionChecks; }

    // --- Channel accessors ---
    public Counter channelMessagesInbound() { return channelMessagesInbound; }
    public Counter channelMessagesOutbound() { return channelMessagesOutbound; }
    public Timer channelMessageLatency() { return channelMessageLatency; }
    public AtomicInteger channelSessionsActive() { return channelSessionsActive; }
    public Counter channelErrors() { return channelErrors; }

    // --- Memory accessors ---
    public AtomicInteger memoryEntriesTotal() { return memoryEntriesTotal; }
    public Timer memorySearchDuration() { return memorySearchDuration; }
    public Counter memoryOperations() { return memoryOperations; }

    public String scrape() {
        StringBuilder sb = new StringBuilder();
        registry.forEachMeter(meter -> {
            sb.append("# TYPE ").append(meter.getId().getName())
                    .append(" ").append(meter.getId().getType()).append("\n");
            meter.measure().forEach(m -> {
                sb.append(meter.getId().getName());
                meter.getId().getTags().forEach(t -> sb.append("{").append(t.getKey())
                        .append("=\"").append(t.getValue()).append("\"}"));
                sb.append(" ").append(m.getValue()).append("\n");
            });
        });
        return sb.toString();
    }

    @Override
    public void close() {
        registry.close();
    }
}
