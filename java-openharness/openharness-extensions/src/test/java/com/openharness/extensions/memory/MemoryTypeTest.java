package com.openharness.extensions.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryTypeTest {

    @Test
    void shouldHaveFourTypes() {
        assertEquals(4, MemoryType.values().length);
    }

    @Test
    void valueOfShouldWork() {
        assertEquals(MemoryType.USER, MemoryType.valueOf("USER"));
        assertEquals(MemoryType.FEEDBACK, MemoryType.valueOf("FEEDBACK"));
        assertEquals(MemoryType.PROJECT, MemoryType.valueOf("PROJECT"));
        assertEquals(MemoryType.REFERENCE, MemoryType.valueOf("REFERENCE"));
    }
}
