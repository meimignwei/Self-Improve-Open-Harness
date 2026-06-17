package com.openharness.extensions.services;

import java.util.Map;

/**
 * Structured compact asset carried across a compaction boundary.
 * Matching Python's CompactAttachment dataclass.
 */
public record CompactAttachment(
        String kind,
        String title,
        String body,
        Map<String, Object> metadata) {

    public CompactAttachment {
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
