package io.openharness.core.events;

import java.time.Instant;
import java.util.UUID;

public record ToolExecutedEvent(
        String eventId, Instant timestamp, String source,
        String toolName, long durationMs, boolean isError, int outputLength) implements OhEvent {

    public ToolExecutedEvent {
        if (eventId == null) eventId = UUID.randomUUID().toString();
        if (timestamp == null) timestamp = Instant.now();
    }

    public ToolExecutedEvent(String source, String toolName, long durationMs, boolean isError, int outputLength) {
        this(UUID.randomUUID().toString(), Instant.now(), source, toolName, durationMs, isError, outputLength);
    }
}
