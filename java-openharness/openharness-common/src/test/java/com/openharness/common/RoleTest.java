package com.openharness.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoleTest {

    @Test
    void shouldHaveThreeRoles() {
        assertEquals(3, Role.values().length);
    }

    @Test
    void valueOfShouldWork() {
        assertEquals(Role.USER, Role.valueOf("USER"));
        assertEquals(Role.ASSISTANT, Role.valueOf("ASSISTANT"));
        assertEquals(Role.SYSTEM, Role.valueOf("SYSTEM"));
    }
}
