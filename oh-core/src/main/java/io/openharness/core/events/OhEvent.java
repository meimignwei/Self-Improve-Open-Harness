package io.openharness.core.events;

import java.time.Instant;

public interface OhEvent {
    String eventId();
    Instant timestamp();
    String source();
}
