package com.openharness.extensions.services;

import java.util.Set;

/**
 * All compaction constants matching Python's services/compact/__init__.py.
 */
public final class CompactionConstants {

    private CompactionConstants() {}

    /** Tools whose results are eligible for micro-compaction clearing. */
    public static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "read_file", "bash", "grep", "glob",
            "web_search", "web_fetch", "edit_file", "write_file");

    /** Marker text replacing cleared tool results. */
    public static final String TIME_BASED_MC_CLEARED_MESSAGE = "[Old tool result content cleared]";

    // Auto-compact thresholds
    public static final int AUTOCOMPACT_BUFFER_TOKENS = 13_000;
    public static final int MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20_000;
    public static final int MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3;
    public static final int COMPACT_TIMEOUT_SECONDS = 25;
    public static final int MAX_COMPACT_STREAMING_RETRIES = 2;
    public static final int MAX_PTL_RETRIES = 3;

    // Session memory
    public static final int SESSION_MEMORY_KEEP_RECENT = 12;
    public static final int SESSION_MEMORY_MAX_LINES = 48;
    public static final int SESSION_MEMORY_MAX_CHARS = 4_000;

    // Context collapse
    public static final int CONTEXT_COLLAPSE_TEXT_CHAR_LIMIT = 2_400;
    public static final int CONTEXT_COLLAPSE_HEAD_CHARS = 900;
    public static final int CONTEXT_COLLAPSE_TAIL_CHARS = 500;

    // Attachments and tools
    public static final int MAX_COMPACT_ATTACHMENTS = 6;
    public static final int MAX_DISCOVERED_TOOLS = 12;

    // Microcompact defaults
    public static final int DEFAULT_KEEP_RECENT = 5;
    public static final int DEFAULT_GAP_THRESHOLD_MINUTES = 60;

    // Token estimation padding (conservative: 4/3)
    public static final double TOKEN_ESTIMATION_PADDING = 4.0 / 3.0;

    // Default context window
    public static final int DEFAULT_CONTEXT_WINDOW = 200_000;
    public static final int DEFAULT_VISION_IMAGE_TOKEN_ESTIMATE = 3_072;

    // Compact message
    public static final String PTL_RETRY_MARKER = "[earlier conversation truncated for compaction retry]";
    public static final String ERROR_MESSAGE_INCOMPLETE_RESPONSE =
            "Compaction interrupted before a complete summary was returned.";

    /** Minimum chars for a tool result to be considered microcompactable. */
    public static final int DEFAULT_MICROCOMPACT_TOOL_RESULT_CHARS = 4_000;
}
