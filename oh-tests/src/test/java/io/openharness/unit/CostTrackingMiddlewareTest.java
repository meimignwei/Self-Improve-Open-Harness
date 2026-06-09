package io.openharness.unit;

import io.agentscope.core.middleware.MiddlewareContext;
import io.openharness.core.middleware.CostTrackingMiddleware;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.byLessThan;

class CostTrackingMiddlewareTest {

    private final CostTrackingMiddleware middleware = new CostTrackingMiddleware();

    @Test
    void shouldTrackSingleTurnTokens() {
        MiddlewareContext ctx = new MiddlewareContext();
        ctx.setAttribute("sessionId", "test");
        ctx.setAttribute("turnNumber", 1);
        ctx.setAttribute("model", "claude-sonnet-4-6");
        ctx.setAttribute("inputTokens", 1000);
        ctx.setAttribute("outputTokens", 500);

        middleware.onModelCall(ctx);

        assertThat((Long) ctx.getAttribute("totalInputTokens")).isEqualTo(1000);
        assertThat((Long) ctx.getAttribute("totalOutputTokens")).isEqualTo(500);
        double cost = (Double) ctx.getAttribute("sessionCost");
        double expected = (1000.0 / 1_000_000) * 3.0 + (500.0 / 1_000_000) * 15.0;
        assertThat(cost).isCloseTo(expected, byLessThan(0.0001));
    }

    @Test
    void shouldAccumulateAcrossMultipleTurns() {
        MiddlewareContext ctx = new MiddlewareContext();
        ctx.setAttribute("sessionId", "test");

        // Turn 1
        ctx.setAttribute("turnNumber", 1);
        ctx.setAttribute("inputTokens", 1000);
        ctx.setAttribute("outputTokens", 500);
        middleware.onModelCall(ctx);

        // Turn 2
        ctx.setAttribute("turnNumber", 2);
        ctx.setAttribute("inputTokens", 2000);
        ctx.setAttribute("outputTokens", 1000);
        middleware.onModelCall(ctx);

        assertThat((Long) ctx.getAttribute("totalInputTokens")).isEqualTo(3000);
        assertThat((Long) ctx.getAttribute("totalOutputTokens")).isEqualTo(1500);
        double expected = (3000.0 / 1_000_000) * 3.0 + (1500.0 / 1_000_000) * 15.0;
        assertThat((Double) ctx.getAttribute("sessionCost"))
                .isCloseTo(expected, byLessThan(0.0001));
    }

    @Test
    void shouldUseOpusPricing() {
        MiddlewareContext ctx = new MiddlewareContext();
        ctx.setAttribute("model", "claude-opus-4-7");
        ctx.setAttribute("inputTokens", 1_000_000);
        ctx.setAttribute("outputTokens", 1_000_000);

        middleware.onModelCall(ctx);

        double cost = (Double) ctx.getAttribute("sessionCost");
        assertThat(cost).isCloseTo(15.0 + 75.0, byLessThan(0.01));
    }

    @Test
    void shouldSkipWhenZeroTokens() {
        MiddlewareContext ctx = new MiddlewareContext();
        ctx.setAttribute("inputTokens", 0);
        ctx.setAttribute("outputTokens", 0);

        middleware.onModelCall(ctx);

        assertThat(ctx.getAttribute("totalInputTokens")).isNull();
        assertThat(ctx.getAttribute("totalOutputTokens")).isNull();
        assertThat(ctx.getAttribute("sessionCost")).isNull();
    }

    @Test
    void shouldUseHaikuPricing() {
        MiddlewareContext ctx = new MiddlewareContext();
        ctx.setAttribute("model", "claude-haiku-4-5");
        ctx.setAttribute("inputTokens", 1_000_000);
        ctx.setAttribute("outputTokens", 1_000_000);

        middleware.onModelCall(ctx);

        double cost = (Double) ctx.getAttribute("sessionCost");
        assertThat(cost).isCloseTo(0.80 + 4.0, byLessThan(0.01));
    }
}
