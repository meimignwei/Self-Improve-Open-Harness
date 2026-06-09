package io.openharness.core.persistence.model;

import java.time.Instant;

public record Session(
    String id,
    String status,
    String model,
    int maxTurns,
    double cost,
    Instant createdAt,
    Instant updatedAt
) {}
