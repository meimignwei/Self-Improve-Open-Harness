package com.openharness.extensions.autopilot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Type definitions for the autopilot system.
 * Java equivalent of Python autopilot/types.py.
 */
public final class AutopilotTypes {

    private AutopilotTypes() {}

    public enum TaskSourceKind {
        OHMO_REQUEST, MANUAL_IDEA, GITHUB_ISSUE, GITHUB_PR, CLAUDE_CODE_CANDIDATE
    }

    public enum TaskStatus {
        QUEUED, ACCEPTED, PREPARING, RUNNING, VERIFYING, PR_OPEN,
        WAITING_CI, REPAIRING, COMPLETED, MERGED, FAILED, REJECTED, SUPERSEDED
    }

    public record RepoTaskCard(
            String id,
            String fingerprint,
            String title,
            String body,
            TaskSourceKind sourceKind,
            String sourceRef,
            TaskStatus status,
            double score,
            List<String> scoreReasons,
            List<String> labels,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt
    ) {
        public RepoTaskCard {
            if (id == null) id = java.util.UUID.randomUUID().toString();
            if (scoreReasons == null) scoreReasons = List.of();
            if (labels == null) labels = List.of();
            if (metadata == null) metadata = Map.of();
        }
    }

    public record AutopilotPolicies(
            Map<String, Object> autopilot,
            Map<String, Object> verification,
            Map<String, Object> release
    ) {}
}
