package com.openharness.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UsageSnapshotTest {

    @Test
    void totalTokensShouldSumInputAndOutput() {
        var snapshot = new UsageSnapshot(100, 50);
        assertEquals(150, snapshot.totalTokens());
    }

    @Test
    void zeroTokensShouldReturnZero() {
        var snapshot = new UsageSnapshot(0, 0);
        assertEquals(0, snapshot.totalTokens());
    }
}
