package com.openharness.extensions.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemorySignatureTest {

    @Test
    void computeShouldReturn64CharHexString() {
        String sig = MemorySignature.compute("test body", "user", "knowledge");
        assertNotNull(sig);
        assertEquals(64, sig.length());
        assertTrue(sig.matches("[0-9a-f]+"));
    }

    @Test
    void computeShouldHandleNullBody() {
        String sig = MemorySignature.compute(null, "user", "knowledge");
        assertNotNull(sig);
        assertEquals(64, sig.length());
    }

    @Test
    void sameInputShouldProduceSameSignature() {
        String a = MemorySignature.compute("bar", "user", "knowledge");
        String b = MemorySignature.compute("bar", "user", "knowledge");
        assertEquals(a, b);
    }

    @Test
    void differentBodyShouldProduceDifferentSignature() {
        String a = MemorySignature.compute("bar", "user", "knowledge");
        String b = MemorySignature.compute("baz", "user", "knowledge");
        assertNotEquals(a, b);
    }

    @Test
    void differentTypeShouldProduceDifferentSignature() {
        String a = MemorySignature.compute("body", "user", "knowledge");
        String b = MemorySignature.compute("body", "feedback", "knowledge");
        assertNotEquals(a, b);
    }

    @Test
    void differentCategoryShouldProduceDifferentSignature() {
        String a = MemorySignature.compute("body", "user", "knowledge");
        String b = MemorySignature.compute("body", "user", "deployment");
        assertNotEquals(a, b);
    }

    @Test
    void normalizationShouldCollapseWhitespace() {
        String a = MemorySignature.compute("hello   world", "user", "knowledge");
        String b = MemorySignature.compute("hello world", "user", "knowledge");
        assertEquals(a, b);
    }

    @Test
    void normalizationShouldBeLowerCase() {
        String a = MemorySignature.compute("HELLO WORLD", "USER", "KNOWLEDGE");
        String b = MemorySignature.compute("hello world", "user", "knowledge");
        assertEquals(a, b);
    }

    @Test
    void normalizationShouldStripPunctuation() {
        String a = MemorySignature.compute("hello, world!", "user", "knowledge");
        String b = MemorySignature.compute("hello world", "user", "knowledge");
        assertEquals(a, b);
    }

    @Test
    void deprecatedTwoArgComputeShouldWork() {
        @SuppressWarnings("deprecation")
        String sig = MemorySignature.compute("ignored-name", "test body");
        assertNotNull(sig);
        assertEquals(64, sig.length());
        // Should match 3-arg version with defaults
        assertEquals(sig, MemorySignature.compute("test body", "project", "knowledge"));
    }
}
