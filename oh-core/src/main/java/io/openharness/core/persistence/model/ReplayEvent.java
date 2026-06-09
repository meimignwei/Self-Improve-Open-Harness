package io.openharness.core.persistence.model;

import java.time.Instant;

public record ReplayEvent(
    String id,
    String sessionId,
    int turnNumber,
    String eventType,
    String requestJson,
    String responseJson,
    String toolArgsJson,
    String toolResultJson,
    Instant createdAt
) {}
