package io.openharness.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Function;

public class CostTrackingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(CostTrackingMiddleware.class);

    private static final Map<String, double[]> MODEL_PRICING = Map.of(
            "sonnet", new double[]{3.0, 15.0},
            "opus", new double[]{15.0, 75.0},
            "haiku", new double[]{0.80, 4.0}
    );
    private static final double[] DEFAULT_PRICING = {3.0, 15.0};

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                         Function<ModelCallInput, Flux<AgentEvent>> next) {
        String modelName = input.model().getModelName();
        log.debug("CostTrackingMiddleware: model call with {}", modelName);
        ctx.put("model", modelName);

        return next.apply(input).doOnNext(event -> {
            if (event instanceof ModelCallEndEvent end) {
                var usage = end.getUsage();
                if (usage != null) {
                    int inputTokens = usage.getInputTokens();
                    int outputTokens = usage.getOutputTokens();
                    if (inputTokens > 0 || outputTokens > 0) {
                        trackCost(ctx, modelName, inputTokens, outputTokens);
                    }
                }
            }
        });
    }

    private void trackCost(RuntimeContext ctx, String model, int inputTokens, int outputTokens) {
        long totalInput = safeLong(ctx.get("totalInputTokens")) + inputTokens;
        long totalOutput = safeLong(ctx.get("totalOutputTokens")) + outputTokens;

        double[] pricing = resolvePricing(model);
        double inputCost = (inputTokens / 1_000_000.0) * pricing[0];
        double outputCost = (outputTokens / 1_000_000.0) * pricing[1];
        double turnCost = inputCost + outputCost;
        double sessionCost = safeDouble(ctx.get("sessionCost")) + turnCost;

        ctx.put("totalInputTokens", totalInput);
        ctx.put("totalOutputTokens", totalOutput);
        ctx.put("sessionCost", sessionCost);

        log.info("Turn: +{}/{} in/out tokens, ${} cost; session total: {}/{}, ${}",
                inputTokens, outputTokens,
                String.format("%.4f", turnCost),
                totalInput, totalOutput,
                String.format("%.4f", sessionCost));
    }

    private double[] resolvePricing(String model) {
        if (model == null) return DEFAULT_PRICING;
        String lower = model.toLowerCase();
        if (lower.contains("opus")) return MODEL_PRICING.get("opus");
        if (lower.contains("haiku")) return MODEL_PRICING.get("haiku");
        if (lower.contains("sonnet")) return MODEL_PRICING.get("sonnet");
        return DEFAULT_PRICING;
    }

    private static int safeInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        return 0;
    }

    private static long safeLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        return 0;
    }

    private static double safeDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
