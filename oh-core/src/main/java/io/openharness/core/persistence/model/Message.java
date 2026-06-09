package io.openharness.core.persistence.model;

import java.time.Instant;

public record Message(
    String id,
    String sessionId,
    int turnNumber,
    String role,
    String content,
    String toolName,
    String toolUseId,
    Instant createdAt
) {}
