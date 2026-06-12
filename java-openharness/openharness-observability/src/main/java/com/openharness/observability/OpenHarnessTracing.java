package com.openharness.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry tracing infrastructure.
 * Provides Span lifecycle management and cross-process trace context propagation.
 */
public class OpenHarnessTracing implements AutoCloseable {

    private static final String INSTRUMENTATION_NAME = "com.openharness";

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public OpenHarnessTracing() {
        this(null);
    }

    public OpenHarnessTracing(String otlpEndpoint) {
        Resource resource = Resource.create(Attributes.builder()
                .put("service.name", "openharness")
                .put("service.version", "0.1.0")
                .build());

        var tracerProviderBuilder = SdkTracerProvider.builder()
                .setResource(resource);

        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(otlpEndpoint)
                    .setTimeout(30, TimeUnit.SECONDS)
                    .build();
            tracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }

        this.openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProviderBuilder.build())
                .buildAndRegisterGlobal();

        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    public Tracer tracer() { return tracer; }

    // --- Span helpers ---

    /**
     * Start a new top-level agent session span.
     */
    public Span startSessionSpan(String sessionId) {
        return tracer.spanBuilder("agent.session")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(SemanticAttributes.AGENT_SESSION_ID, sessionId)
                .startSpan();
    }

    /**
     * Start a loop iteration span as child of the current context.
     */
    public Span startLoopSpan(int turnNumber, int toolCount) {
        return tracer.spanBuilder("agent.loop.iteration")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(SemanticAttributes.AGENT_TURN_NUMBER, turnNumber)
                .setAttribute(SemanticAttributes.AGENT_TOOL_COUNT, toolCount)
                .startSpan();
    }

    /**
     * Start an LLM API call span.
     */
    public Span startApiCallSpan(String provider, String model) {
        return tracer.spanBuilder("llm.api.call")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.LLM_PROVIDER, provider)
                .setAttribute(SemanticAttributes.LLM_MODEL, model)
                .startSpan();
    }

    /**
     * Start a tool execution span.
     */
    public Span startToolSpan(String toolName) {
        return tracer.spanBuilder("tool.execute")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(SemanticAttributes.TOOL_NAME, toolName)
                .startSpan();
    }

    // --- Trace Context Propagation ---

    /**
     * Inject trace context into a carrier map (for sub-process environment variables).
     */
    public Map<String, String> inject(Context context) {
        Map<String, String> carrier = new HashMap<>();
        TextMapPropagator propagator = openTelemetry.getPropagators().getTextMapPropagator();
        propagator.inject(context, carrier, Map::put);
        return carrier;
    }

    /**
     * Extract trace context from environment variables.
     */
    public Context extract(Map<String, String> env) {
        TextMapPropagator propagator = openTelemetry.getPropagators().getTextMapPropagator();
        return propagator.extract(Context.current(), env, new MapTextMapGetter());
    }

    public OpenTelemetry openTelemetry() { return openTelemetry; }

    @Override
    public void close() {
        if (openTelemetry instanceof OpenTelemetrySdk sdk) {
            sdk.close();
        }
    }

    /**
     * TextMapGetter adapter for Map<String, String>.
     */
    private static class MapTextMapGetter implements TextMapGetter<Map<String, String>> {
        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier != null ? carrier.get(key) : null;
        }

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier != null ? carrier.keySet() : java.util.Collections.emptySet();
        }
    }
}
