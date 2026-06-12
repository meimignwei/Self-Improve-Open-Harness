package com.openharness.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryOptionsTest {

    @Test
    void defaultsShouldHaveEmptyOptionals() {
        var opts = QueryOptions.defaults();
        assertTrue(opts.model().isEmpty());
        assertTrue(opts.systemPrompt().isEmpty());
        assertTrue(opts.maxTurns().isEmpty());
    }

    @Test
    void withModelShouldSetModelAndPreserveOthers() {
        var opts = QueryOptions.defaults().withModel("claude-4");
        assertTrue(opts.model().isPresent());
        assertEquals("claude-4", opts.model().get());
        assertTrue(opts.systemPrompt().isEmpty());
    }

    @Test
    void withMaxTurnsShouldSetMaxTurns() {
        var opts = QueryOptions.defaults().withMaxTurns(15);
        assertEquals(15, opts.maxTurns().orElse(0));
    }
}
