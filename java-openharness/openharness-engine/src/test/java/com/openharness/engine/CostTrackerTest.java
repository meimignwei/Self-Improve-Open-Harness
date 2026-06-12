package com.openharness.engine;

import com.openharness.common.UsageSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CostTrackerTest {

    @Test
    void shouldAccumulateUsage() {
        CostTracker tracker = new CostTracker();
        tracker.add(new UsageSnapshot(1000, 500), "gpt-4o");
        tracker.add(new UsageSnapshot(2000, 1000), "gpt-4o");

        // totalTokens = (1000+500) + (2000+1000) = 4500 after merging per-model
        assertEquals(4500, tracker.getTotalTokens());
        assertTrue(tracker.getTotalCost() > 0);
    }

    @Test
    void shouldTrackByModel() {
        CostTracker tracker = new CostTracker();
        tracker.add(new UsageSnapshot(1000, 0), "gpt-4o");
        tracker.add(new UsageSnapshot(0, 500), "claude-sonnet-4-6");

        assertEquals(2, tracker.getUsageByModel().size());
    }

    @Test
    void shouldHandleNullUsage() {
        CostTracker tracker = new CostTracker();
        tracker.add(null, "gpt-4o");
        assertEquals(0, tracker.getTotalTokens());
        assertEquals(0.0, tracker.getTotalCost());
    }

    @Test
    void shouldReset() {
        CostTracker tracker = new CostTracker();
        tracker.add(new UsageSnapshot(100, 100), "gpt-4o");
        tracker.reset();
        assertEquals(0, tracker.getTotalTokens());
        assertEquals(0.0, tracker.getTotalCost());
    }

    @Test
    void computeCostForKnownModel() {
        double cost = CostTracker.computeCost(new UsageSnapshot(1000, 1000), "gpt-4o");
        assertEquals(0.02, cost, 0.0001);
    }

    @Test
    void computeCostForUnknownModelReturnsZero() {
        double cost = CostTracker.computeCost(new UsageSnapshot(1000, 1000), "unknown-model");
        assertEquals(0.0, cost);
    }
}
