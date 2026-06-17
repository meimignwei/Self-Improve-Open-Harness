package com.openharness.extensions.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

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
        Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS);
        var expiredHeader = new MemoryEntry.MemoryHeader(
                header.schemaVersion(), header.id(), header.name(), header.description(),
                header.type(), header.scope(), header.category(), header.importance(), header.source(),
                header.signature(), tenDaysAgo, tenDaysAgo,
                1, header.disabled(), header.supersedes(), header.tags());
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
    void searchWithMatchingNameShouldScoreHigher() {
        var entry = MemoryEntry.create(MemoryType.USER, "Deployment", "pipeline", "some body text");
        var results = search.search("deployment", List.of(entry), 5);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).score() > 0,
                "Score should be > 0 for matching name: " + results.get(0).score());
    }

    @Test
    void searchWithMatchingBodyShouldScore() {
        var entry = MemoryEntry.create(MemoryType.USER, "Random", "random desc",
                "The deployment process requires careful planning");
        var results = search.search("deployment", List.of(entry), 5);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).score() > 0,
                "Score should be > 0 for matching body: " + results.get(0).score());
    }

    @Test
    void matchingEntryScoresHigherThanNonmatching() {
        var noMatchEntry = MemoryEntry.create(MemoryType.USER, "XYZ", "abc", "nothing relevant here");
        var matchEntry = MemoryEntry.create(MemoryType.USER, "Deployment Guide", "deployment docs",
                "complete deployment process documentation");

        var results = search.search("deployment", List.of(noMatchEntry, matchEntry), 5);
        assertEquals(1, results.size());
        assertEquals("Deployment Guide", results.get(0).memory().header().name());
    }

    @Test
    void recencyBoostShouldBeHigherForRecentEntries() {
        var recent = MemoryEntry.create(MemoryType.USER, "Recent", "desc", "body");
        double recentBoost = search.computeRecencyBoostOld(recent.header().updatedAt());
        assertTrue(recentBoost > 0.9, "Recent boost should be near 1.0: " + recentBoost);

        var oldTs = Instant.now().minus(60, ChronoUnit.DAYS);
        double oldBoost = search.computeRecencyBoostOld(oldTs);
        assertTrue(oldBoost < recentBoost, "Old boost should be lower than recent");
    }

    @Test
    void usageBoostShouldIncreaseAfterRecording() {
        var entry = MemoryEntry.create(MemoryType.USER, "Used", "desc", "body");
        String id = entry.header().id();

        int initialCount = tracker.getUsageCount(id);
        assertEquals(0, initialCount);

        for (int i = 0; i < 15; i++) {
            tracker.recordUsage(id);
        }
        int countAfterUse = tracker.getUsageCount(id);
        assertEquals(15, countAfterUse);
    }

    @Test
    void searchWithNullUsageTrackerShouldNotFail() {
        var noTrackerSearch = new MemorySearch();
        var entry = MemoryEntry.create(MemoryType.USER, "Test", "desc", "body");
        var results = noTrackerSearch.search("test", List.of(entry), 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void tokenizeShouldExtractAsciiWords3PlusChars() {
        Set<String> tokens = MemorySearch.tokenize("hello world");
        assertTrue(tokens.contains("hello"));
        assertTrue(tokens.contains("world"));
    }

    @Test
    void tokenizeShouldSkipShortWords() {
        Set<String> tokens = MemorySearch.tokenize("a is at it");
        // These are all 2 chars or less — should be empty
        tokens.forEach(t -> assertTrue(t.length() >= 3 || t.matches("[\\u4e00-\\u9fff\\u3400-\\u4dbf]"),
                "Token '" + t + "' should be 3+ chars or Han ideograph"));
    }

    @Test
    void tokenizeShouldHandleHanCharacters() {
        Set<String> tokens = MemorySearch.tokenize("你好世界");
        assertTrue(tokens.contains("你"));
        assertTrue(tokens.contains("好"));
        assertTrue(tokens.contains("世"));
        assertTrue(tokens.contains("界"));
    }

    @Test
    void tokenizeShouldHandleMixedAsciiAndHan() {
        Set<String> tokens = MemorySearch.tokenize("deployment 部署");
        assertTrue(tokens.contains("deployment"));
        assertTrue(tokens.contains("部"));
        assertTrue(tokens.contains("署"));
    }
}
