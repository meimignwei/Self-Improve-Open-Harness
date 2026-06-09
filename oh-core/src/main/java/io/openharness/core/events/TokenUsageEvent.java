package io.openharness.core.events;

import java.time.Instant;
import java.util.UUID;

public record TokenUsageEvent(
        String eventId, Instant timestamp, String source,
        int inputTokens, int outputTokens, double cost) implements OhEvent {

    public TokenUsageEvent {
        if (eventId == null) eventId = UUID.randomUUID().toString();
        if (timestamp == null) timestamp = Instant.now();
    }

    public TokenUsageEvent(String source, int inputTokens, int outputTokens, double cost) {
        this(UUID.randomUUID().toString(), Instant.now(), source, inputTokens, outputTokens, cost);
    }
}
