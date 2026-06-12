package com.openharness.extensions.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemorySearchTest {

    private MemorySearch search;
    private MemoryUsageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new MemoryUsageTracker();
        search = new MemorySearch(tracker);
    }

    @Test
    void searchShouldFilterDisabledMemories() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var disabled = entry.withDisabled(true);

        var results = search.search("test", List.of(entry, disabled), 10);
        assertEquals(1, results.size());
        assertEquals(entry.header().id(), results.get(0).memory().header().id());
    }

    @Test
    void searchShouldFilterExpiredMemories() {
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var header = entry.header();
        var expiredHeader = new MemoryEntry.MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.category(), header.importance(), header.source(),
                header.signature(), Instant.now().minus(10, ChronoUnit.DAYS), header.updatedAt(),
                1, header.disabled(), header.supersedes());
        var expired = new MemoryEntry(expiredHeader, entry.body());

        var results = search.search("test", List.of(expired), 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchShouldReturnTopKResults() {
        var e1 = MemoryEntry.create(MemoryType.USER, "Alpha", "first", "content about alpha");
        var e2 = MemoryEntry.create(MemoryType.USER, "Beta", "second", "content about beta");

        var results = search.search("alpha", List.of(e1, e2), 1);
        assertEquals(1, results.size());
    }

    @Test
    void scoreWithMatchingNameShouldBeHigher() {
        var entry = MemoryEntry.create(MemoryType.USER, "Deployment", "pipeline", "some body text");
        double score = search.score("deployment", entry, new java.util.HashMap<>());
        assertTrue(score > 0, "Score should be > 0 for matching name: " + score);
    }

    @Test
    void scoreWithMatchingBodyShouldBeHigher() {
        var entry = MemoryEntry.create(MemoryType.USER, "Random", "random desc",
                "The deployment process requires careful planning");
        double score = search.score("deployment", entry, new java.util.HashMap<>());
        assertTrue(score > 0, "Score should be > 0 for matching body: " + score);
    }

    @Test
    void scoreWithNoMatchShouldBeLow() {
        var entry = MemoryEntry.create(MemoryType.USER, "XYZ", "abc", "nothing relevant here");
        double noMatchScore = search.score("deployment", entry, new java.util.HashMap<>());

        var matchingEntry = MemoryEntry.create(MemoryType.USER, "Deployment Guide", "deployment docs",
                "complete deployment process documentation");
        double matchScore = search.score("deployment", matchingEntry, new java.util.HashMap<>());

        assertTrue(matchScore > noMatchScore,
                "Matching entry score (" + matchScore + ") should exceed non-match (" + noMatchScore + ")");
    }

    @Test
    void recencyBoostShouldBeHigherForRecentEntries() {
        var recent = MemoryEntry.create(MemoryType.USER, "Recent", "desc", "body");
        double recentBoost = search.computeRecencyBoost(recent.header().updatedAt());
        assertTrue(recentBoost > 0.9, "Recent boost should be near 1.0: " + recentBoost);

        var oldTs = Instant.now().minus(60, ChronoUnit.DAYS);
        double oldBoost = search.computeRecencyBoost(oldTs);
        assertTrue(oldBoost < recentBoost, "Old boost should be lower than recent");
    }

    @Test
    void usageBoostShouldIncreaseAfterRecording() {
        var entry = MemoryEntry.create(MemoryType.USER, "Used", "desc", "body");
        String id = entry.header().id();

        double initialBoost = tracker.computeUsageBoost(id);
        assertEquals(0.0, initialBoost);

        for (int i = 0; i < 15; i++) {
            tracker.recordUsage(id);
        }
        double boostAfterUse = tracker.computeUsageBoost(id);
        assertTrue(boostAfterUse > 0, "Usage boost should be > 0 after usage: " + boostAfterUse);
    }

    @Test
    void searchWithNullUsageTrackerShouldNotFail() {
        var noTrackerSearch = new MemorySearch();
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var results = noTrackerSearch.search("test", List.of(entry), 5);
        assertFalse(results.isEmpty());
    }
}
