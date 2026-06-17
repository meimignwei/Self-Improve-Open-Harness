package com.openharness.engine;

import java.util.UUID;

/**
 * Mutable state that persists across query loop turns.
 * Matching Python's AutoCompactState dataclass.
 */
public class AutoCompactState {

    private boolean compacted;
    private int turnCounter;
    private String turnId;
    private int consecutiveFailures;

    public AutoCompactState() {
        this.compacted = false;
        this.turnCounter = 0;
        this.turnId = "";
        this.consecutiveFailures = 0;
    }

    public boolean compacted() { return compacted; }
    public void setCompacted(boolean v) { this.compacted = v; }

    public int turnCounter() { return turnCounter; }
    public void incrementTurn() { this.turnCounter++; }

    public String turnId() { return turnId; }
    public void newTurnId() { this.turnId = UUID.randomUUID().toString().replace("-", ""); }

    public int consecutiveFailures() { return consecutiveFailures; }
    public void incrementFailures() { this.consecutiveFailures++; }
    public void resetFailures() { this.consecutiveFailures = 0; }
}
