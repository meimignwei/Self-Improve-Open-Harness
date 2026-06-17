package com.openharness.extensions.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content signature for memory deduplication.
 * Java equivalent of Python memory/schema.py compute_memory_signature.
 */
public final class MemorySignature {

    private MemorySignature() {}

    /**
     * Normalize memory content for deterministic signatures.
     * Matches Python normalize_memory_content: lowercase → collapse whitespace → strip punctuation.
     */
    public static String normalizeContent(String text) {
        if (text == null) return "";
        String lowered = text.toLowerCase();
        String collapsed = lowered.replaceAll("\\s+", " ");
        return collapsed.replaceAll("\\p{Punct}", "").strip();
    }

    /**
     * Compute a deterministic content signature for duplicate detection.
     * Python: SHA-256(normalized_content|type|category) → full 64-char hex digest.
     */
    public static String compute(String content, String memoryType, String category) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalized = normalizeContent(content);
            String payload = normalized + "|"
                    + (memoryType != null ? memoryType.strip().toLowerCase() : "")
                    + "|" + (category != null ? category.strip().toLowerCase() : "");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Legacy compatibility — delegates to the correct signature.
     * @deprecated Use {@link #compute(String, String, String)} with type and category.
     */
    @Deprecated
    public static String compute(String name, String body) {
        return compute(body, "project", "knowledge");
    }
}
