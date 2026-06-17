package com.openharness.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoCompactStateTest {

    @Test
    void shouldStartWithDefaults() {
        AutoCompactState state = new AutoCompactState();
        assertFalse(state.compacted());
        assertEquals(0, state.turnCounter());
        assertEquals(0, state.consecutiveFailures());
    }

    @Test
    void shouldTrackCompacted() {
        AutoCompactState state = new AutoCompactState();
        assertFalse(state.compacted());
        state.setCompacted(true);
        assertTrue(state.compacted());
    }

    @Test
    void shouldTrackTurnCounter() {
        AutoCompactState state = new AutoCompactState();
        assertEquals(0, state.turnCounter());
        state.incrementTurn();
        assertEquals(1, state.turnCounter());
        state.incrementTurn();
        assertEquals(2, state.turnCounter());
    }

    @Test
    void shouldGenerateTurnId() {
        AutoCompactState state = new AutoCompactState();
        assertTrue(state.turnId().isEmpty());
        state.newTurnId();
        assertFalse(state.turnId().isEmpty());
        String first = state.turnId();
        state.newTurnId();
        assertNotEquals(first, state.turnId());
    }

    @Test
    void shouldTrackConsecutiveFailures() {
        AutoCompactState state = new AutoCompactState();
        assertEquals(0, state.consecutiveFailures());
        state.incrementFailures();
        assertEquals(1, state.consecutiveFailures());
        state.incrementFailures();
        assertEquals(2, state.consecutiveFailures());
        state.resetFailures();
        assertEquals(0, state.consecutiveFailures());
    }
}
