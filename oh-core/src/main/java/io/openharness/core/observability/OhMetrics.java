package io.openharness.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

/**
 * 所有指标通过 Micrometer 暴露。
 * MVP 提供两种 registry:
 *   - LoggingMeterRegistry: 输出到 metrics.log (本地开发)
 *   - PrometheusMeterRegistry: 通过内置 HTTP server 暴露 :8080/metrics (server/container 模式)
 * 通过 OH_METRICS_MODE 环境变量切换: logging (默认) | prometheus
 * 抓取端口默认 8080，可通过 OH_METRICS_PORT 覆盖
 */
public class OhMetrics {

    private static final Logger log = LoggerFactory.getLogger(OhMetrics.class);

    public static final String AGENT_TURNS = "oh.agent.turns";
    public static final String AGENT_TURN_DURATION = "oh.agent.turn.duration";
    public static final String API_CALLS = "oh.api.calls";
    public static final String API_LATENCY = "oh.api.latency";
    public static final String API_RETRIES = "oh.api.retries";
    public static final String API_ERRORS = "oh.api.errors";
    public static final String API_TOKENS_INPUT = "oh.api.tokens.input";
    public static final String API_TOKENS_OUTPUT = "oh.api.tokens.output";
    public static final String API_COST = "oh.api.cost";
    public static final String TOOL_CALLS = "oh.tool.calls";
    public static final String TOOL_LATENCY = "oh.tool.latency";
    public static final String TOOL_ERRORS = "oh.tool.errors";
    public static final String TOOL_OUTPUT_SIZE = "oh.tool.output.size";
    public static final String SESSION_ACTIVE = "oh.session.active";
    public static final String SESSION_DURATION = "oh.session.duration";
    public static final String MEMORY_HEAP = "oh.jvm.memory.heap";
    public static final String THREAD_COUNT = "oh.jvm.threads";

    private final MeterRegistry registry;
    private HttpServer httpServer;

    public OhMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** 启动内置 HTTP server 暴露 /metrics 供 Prometheus 抓取 */
    public void startHttpServer(int port) {
        if (!(registry instanceof PrometheusMeterRegistry prometheus)) return;
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/metrics", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    prometheus.scrape(out);
                }
            });
            httpServer.setExecutor(null); // default executor
            httpServer.start();
            log.info("Prometheus metrics endpoint started on :{}/metrics", port);
        } catch (Exception e) {
            log.error("Failed to start metrics HTTP server on port {}", port, e);
        }
    }

    public void stopHttpServer() {
        if (httpServer != null) httpServer.stop(0);
    }
}
