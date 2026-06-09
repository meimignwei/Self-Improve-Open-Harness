package io.openharness.core.middleware;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class CostTrackingMiddleware extends MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(CostTrackingMiddleware.class);

    private static final Map<String, double[]> MODEL_PRICING = Map.of(
            "sonnet", new double[]{3.0, 15.0},
            "opus", new double[]{15.0, 75.0},
            "haiku", new double[]{0.80, 4.0}
    );
    private static final double[] DEFAULT_PRICING = {3.0, 15.0};

    @Override
    public void onModelCall(MiddlewareContext ctx) {
        int inputTokens = safeInt(ctx.getAttribute("inputTokens"));
        int outputTokens = safeInt(ctx.getAttribute("outputTokens"));

        if (inputTokens == 0 && outputTokens == 0) {
            log.debug("CostTrackingMiddleware: no tokens consumed, skipping");
            super.onModelCall(ctx);
            return;
        }

        long totalInput = safeLong(ctx.getAttribute("totalInputTokens")) + inputTokens;
        long totalOutput = safeLong(ctx.getAttribute("totalOutputTokens")) + outputTokens;

        String model = getString(ctx.getAttribute("model"));
        double[] pricing = resolvePricing(model);

        double inputCost = (inputTokens / 1_000_000.0) * pricing[0];
        double outputCost = (outputTokens / 1_000_000.0) * pricing[1];
        double turnCost = inputCost + outputCost;

        double sessionCost = safeDouble(ctx.getAttribute("sessionCost")) + turnCost;

        ctx.setAttribute("totalInputTokens", totalInput);
        ctx.setAttribute("totalOutputTokens", totalOutput);
        ctx.setAttribute("sessionCost", sessionCost);

        log.info("Turn {}: +{}/{} in/out tokens, ${} cost; session total: {}/{}, ${}",
                ctx.getTurnNumber(), inputTokens, outputTokens,
                String.format("%.4f", turnCost),
                totalInput, totalOutput,
                String.format("%.4f", sessionCost));

        super.onModelCall(ctx);
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

    private static String getString(Object value) {
        return value != null ? value.toString() : null;
    }
}
