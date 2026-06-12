package com.openharness.extensions.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content signature for memory deduplication.
 * Java equivalent of Python memory/schema.py MemorySignature.
 */
public final class MemorySignature {

    private MemorySignature() {}

    public static String compute(String name, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = name + "::" + (body != null ? body : "");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
