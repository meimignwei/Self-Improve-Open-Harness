package com.openharness.extensions.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemorySignatureTest {

    @Test
    void computeShouldReturn16CharHexString() {
        String sig = MemorySignature.compute("test-name", "test body");
        assertNotNull(sig);
        assertEquals(16, sig.length());
        assertTrue(sig.matches("[0-9a-f]+"));
    }

    @Test
    void computeShouldHandleNullBody() {
        String sig = MemorySignature.compute("name", null);
        assertNotNull(sig);
        assertEquals(16, sig.length());
    }

    @Test
    void sameInputShouldProduceSameSignature() {
        String a = MemorySignature.compute("foo", "bar");
        String b = MemorySignature.compute("foo", "bar");
        assertEquals(a, b);
    }

    @Test
    void differentInputShouldProduceDifferentSignature() {
        String a = MemorySignature.compute("foo", "bar");
        String b = MemorySignature.compute("foo", "baz");
        assertNotEquals(a, b);
    }

    @Test
    void differentNameShouldProduceDifferentSignature() {
        String a = MemorySignature.compute("foo", "body");
        String b = MemorySignature.compute("bar", "body");
        assertNotEquals(a, b);
    }
}
