package io.openharness.unit;

import io.openharness.core.events.EventHandler;
import io.openharness.core.events.ReactorEventBus;
import io.openharness.core.events.TokenUsageEvent;
import io.openharness.core.events.ToolExecutedEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusTest {

    @Test
    void shouldPublishAndSubscribe() throws Exception {
        ReactorEventBus bus = new ReactorEventBus();
        AtomicReference<TokenUsageEvent> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        bus.subscribe(TokenUsageEvent.class, event -> {
            received.set(event);
            latch.countDown();
        });

        TokenUsageEvent sent = new TokenUsageEvent("test-source", 500, 200, 0.015);
        bus.publish(sent);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isNotNull();
        assertThat(received.get().inputTokens()).isEqualTo(500);
        assertThat(received.get().outputTokens()).isEqualTo(200);
        assertThat(received.get().cost()).isEqualTo(0.015);
    }

    @Test
    void shouldFilterEventsByType() throws Exception {
        ReactorEventBus bus = new ReactorEventBus();
        AtomicInteger toolEventCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        bus.subscribe(ToolExecutedEvent.class, event -> {
            toolEventCount.incrementAndGet();
            latch.countDown();
        });

        bus.publish(new ToolExecutedEvent("s1", "web_search", 100, false, 200));
        bus.publish(new TokenUsageEvent("s1", 100, 50, 0.001));
        bus.publish(new ToolExecutedEvent("s2", "web_fetch", 150, false, 300));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(toolEventCount.get()).isEqualTo(2);
    }

    @Test
    void shouldSupportMultipleSubscribers() throws Exception {
        ReactorEventBus bus = new ReactorEventBus();
        AtomicInteger handler1 = new AtomicInteger(0);
        AtomicInteger handler2 = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        bus.subscribe(TokenUsageEvent.class, event -> {
            handler1.incrementAndGet();
            latch.countDown();
        });
        bus.subscribe(TokenUsageEvent.class, event -> {
            handler2.incrementAndGet();
            latch.countDown();
        });

        bus.publish(new TokenUsageEvent("s1", 100, 50, 0.001));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(handler1.get()).isEqualTo(1);
        assertThat(handler2.get()).isEqualTo(1);
    }
}
