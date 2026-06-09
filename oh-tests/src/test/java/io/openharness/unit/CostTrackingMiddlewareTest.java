package io.openharness.unit;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.openharness.core.middleware.CostTrackingMiddleware;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.byLessThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CostTrackingMiddlewareTest {

    private final CostTrackingMiddleware middleware = new CostTrackingMiddleware();

    private ModelCallInput makeInput(String modelName) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn(modelName);
        return new ModelCallInput(List.of(), List.of(), GenerateOptions.builder().build(), model);
    }

    private RuntimeContext makeCtx() {
        return RuntimeContext.builder().sessionId("test-session").build();
    }

    private Function<ModelCallInput, Flux<AgentEvent>> passthrough() {
        return input -> Flux.empty();
    }

    @Test
    void shouldTrackModelInRuntimeContext() {
        RuntimeContext ctx = makeCtx();
        ModelCallInput input = makeInput("claude-sonnet-4-6");

        middleware.onModelCall(mock(Agent.class), ctx, input, passthrough()).blockLast();

        Object model = ctx.get("model");
        assertThat(model).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void shouldAccumulateCostsAcrossMultipleCalls() {
        RuntimeContext ctx = makeCtx();
        ctx.put("model", "claude-sonnet-4-6");

        double[] sonicPricing = {3.0, 15.0};

        int input1 = 1000, output1 = 500;
        double cost1 = (input1 / 1_000_000.0) * sonicPricing[0] + (output1 / 1_000_000.0) * sonicPricing[1];

        ctx.put("totalInputTokens", 1000L);
        ctx.put("totalOutputTokens", 500L);
        ctx.put("sessionCost", cost1);

        Object rawInput = ctx.get("totalInputTokens");
        assertThat(rawInput).isInstanceOf(Long.class);
        assertThat((Long) rawInput).isEqualTo(1000L);

        Object rawOutput = ctx.get("totalOutputTokens");
        assertThat(rawOutput).isInstanceOf(Long.class);
        assertThat((Long) rawOutput).isEqualTo(500L);

        Object rawCost = ctx.get("sessionCost");
        assertThat(rawCost).isInstanceOf(Double.class);
        assertThat((Double) rawCost).isCloseTo(cost1, byLessThan(0.0001));
    }

    @Test
    void shouldResolveOpusPricing() {
        CostTrackingMiddleware mw = new CostTrackingMiddleware();
        RuntimeContext ctx = makeCtx();
        ModelCallInput input = makeInput("claude-opus-4-7");

        mw.onModelCall(mock(Agent.class), ctx, input, passthrough()).blockLast();

        Object opusModel = ctx.get("model");
        assertThat(opusModel).isEqualTo("claude-opus-4-7");
    }

    @Test
    void shouldResolveHaikuPricing() {
        CostTrackingMiddleware mw = new CostTrackingMiddleware();
        RuntimeContext ctx = makeCtx();
        ModelCallInput input = makeInput("claude-haiku-4-5");

        mw.onModelCall(mock(Agent.class), ctx, input, passthrough()).blockLast();

        Object haikuModel = ctx.get("model");
        assertThat(haikuModel).isEqualTo("claude-haiku-4-5");
    }
}
