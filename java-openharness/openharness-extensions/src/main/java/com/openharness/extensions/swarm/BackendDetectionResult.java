package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Result from backend auto-detection.
 * Java equivalent of Python swarm/types.py BackendDetectionResult.
 */
@JsonDeserialize
public record BackendDetectionResult(
        @JsonProperty("backend") String backend,
        @JsonProperty("is_native") boolean isNative,
        @JsonProperty("needs_setup") boolean needsSetup) {

    public BackendDetectionResult {
        // defaults handled by Java's primitive defaults
    }

    public BackendDetectionResult(String backend, boolean isNative) {
        this(backend, isNative, false);
    }
}