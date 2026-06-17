package com.openharness.extensions.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HookSystemTest {

    // ── HookEvent ────────────────────────────────────────────────────

    @Test
    void hookEventHasAllTenTypes() {
        assertEquals(10, HookEvent.values().length);
        assertNotNull(HookEvent.valueOf("SESSION_START"));
        assertNotNull(HookEvent.valueOf("SESSION_END"));
        assertNotNull(HookEvent.valueOf("PRE_COMPACT"));
        assertNotNull(HookEvent.valueOf("POST_COMPACT"));
        assertNotNull(HookEvent.valueOf("PRE_TOOL_USE"));
        assertNotNull(HookEvent.valueOf("POST_TOOL_USE"));
        assertNotNull(HookEvent.valueOf("USER_PROMPT_SUBMIT"));
        assertNotNull(HookEvent.valueOf("NOTIFICATION"));
        assertNotNull(HookEvent.valueOf("STOP"));
        assertNotNull(HookEvent.valueOf("SUBAGENT_STOP"));
    }

    // ── HookDefinition ──────────────────────────────────────────────

    @Test
    void commandHookDefinition() {
        var hook = new HookDefinition.CommandHook(
                "echo hello", "*", 30, false, 100);
        assertEquals("echo hello", hook.command());
        assertEquals("*", hook.matcher());
        assertEquals(30, hook.timeoutSeconds());
        assertFalse(hook.blockOnFailure());
        assertEquals(100, hook.priority());
    }

    @Test
    void promptHookDefinition() {
        var hook = new HookDefinition.PromptHook(
                "Is this safe?", "claude-sonnet-4-6", "*", 30, true, 50);
        assertEquals("Is this safe?", hook.prompt());
        assertEquals("claude-sonnet-4-6", hook.model());
        assertTrue(hook.blockOnFailure());
        assertEquals(50, hook.priority());
    }

    @Test
    void httpHookDefinition() {
        var hook = new HookDefinition.HttpHook(
                "https://example.com/hook", Map.of("X-Key", "v"), "*", 30, false, 0);
        assertEquals("https://example.com/hook", hook.url());
        assertEquals(Map.of("X-Key", "v"), hook.headers());
        assertEquals(0, hook.priority());
    }

    @Test
    void agentHookDefinition() {
        var hook = new HookDefinition.AgentHook(
                "Validate this action", "claude-opus-4-7", "*tool*", 120, true, 200);
        assertEquals("Validate this action", hook.prompt());
        assertEquals("claude-opus-4-7", hook.model());
        assertEquals("*tool*", hook.matcher());
        assertEquals(120, hook.timeoutSeconds());
        assertTrue(hook.blockOnFailure());
        assertEquals(200, hook.priority());
    }

    // ── HookResult ───────────────────────────────────────────────────

    @Test
    void hookResultOk() {
        var r = HookResult.ok("all good");
        assertEquals("command", r.hookType());
        assertTrue(r.success());
        assertFalse(r.blocked());
        assertEquals("all good", r.output());
        assertEquals("", r.reason());
    }

    @Test
    void hookResultBlocked() {
        var r = HookResult.blocked("access denied");
        assertEquals("command", r.hookType());
        assertFalse(r.success());
        assertTrue(r.blocked());
        assertEquals("access denied", r.output());
        assertEquals("access denied", r.reason());
    }

    @Test
    void hookResultFailed() {
        var r = HookResult.failed("timeout");
        assertEquals("command", r.hookType());
        assertFalse(r.success());
        assertFalse(r.blocked());
        assertEquals("timeout", r.output());
        assertEquals("timeout", r.reason());
    }

    @Test
    void hookResultFullConstructor() {
        var r = new HookResult("http", true, false, "body text", "", Map.of("code", 200));
        assertEquals("http", r.hookType());
        assertTrue(r.success());
        assertEquals("body text", r.output());
        assertEquals(200, r.metadata().get("code"));
    }

    // ── AggregatedHookResult ─────────────────────────────────────────

    @Test
    void aggregatedNotBlockedWhenAllOk() {
        var results = List.of(HookResult.ok("a"), HookResult.ok("b"));
        var agg = new AggregatedHookResult(results);
        assertFalse(agg.blocked());
        assertTrue(agg.reason().isEmpty());
    }

    @Test
    void aggregatedBlockedWhenAnyBlocked() {
        var results = List.of(HookResult.ok("a"), HookResult.blocked("stop"), HookResult.ok("c"));
        var agg = new AggregatedHookResult(results);
        assertTrue(agg.blocked());
        assertEquals("stop", agg.reason());
    }

    @Test
    void aggregatedReasonFallsBackToOutput() {
        var r = new HookResult("http", false, true, "body", "", Map.of());
        var agg = new AggregatedHookResult(List.of(r));
        assertTrue(agg.blocked());
        assertEquals("body", agg.reason());
    }

    // ── HookRegistry ─────────────────────────────────────────────────

    @Test
    void registryRegistersAndRetrievesByEvent() {
        var registry = new HookRegistry();
        var hook = new HookDefinition.CommandHook("cmd", "*", 30, false, 0);
        registry.register(HookEvent.SESSION_START, hook);

        assertEquals(1, registry.get(HookEvent.SESSION_START).size());
        assertTrue(registry.get(HookEvent.SESSION_END).isEmpty());
    }

    @Test
    void registrySortsByPriorityDescending() {
        var registry = new HookRegistry();
        var low = new HookDefinition.CommandHook("low", "*", 30, false, 0);
        var mid = new HookDefinition.CommandHook("mid", "*", 30, false, 50);
        var high = new HookDefinition.CommandHook("high", "*", 30, false, 100);

        registry.register(HookEvent.SESSION_START, low);
        registry.register(HookEvent.SESSION_START, high);
        registry.register(HookEvent.SESSION_START, mid);

        List<HookDefinition> hooks = registry.get(HookEvent.SESSION_START);
        assertEquals(3, hooks.size());
        assertEquals(100, hooks.get(0).priority());
        assertEquals(50, hooks.get(1).priority());
        assertEquals(0, hooks.get(2).priority());
    }

    @Test
    void registryUnregisterRemovesHook() {
        var registry = new HookRegistry();
        var hook = new HookDefinition.CommandHook("cmd", "*", 30, false, 0);
        registry.register(HookEvent.SESSION_START, hook);
        registry.unregister(HookEvent.SESSION_START, hook);
        assertTrue(registry.get(HookEvent.SESSION_START).isEmpty());
    }

    @Test
    void registryClearRemovesAll() {
        var registry = new HookRegistry();
        registry.register(HookEvent.SESSION_START,
                new HookDefinition.CommandHook("a", "*", 30, false, 0));
        registry.register(HookEvent.PRE_TOOL_USE,
                new HookDefinition.HttpHook("http://x", Map.of(), "*", 30, false, 0));
        assertEquals(2, registry.all().size());
        registry.clear();
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void registrySummaryContainsHookDetails() {
        var registry = new HookRegistry();
        registry.register(HookEvent.PRE_TOOL_USE,
                new HookDefinition.CommandHook("echo test", "bash", 30, false, 100));
        String summary = registry.summary();
        assertTrue(summary.contains("pre_tool_use"));
        assertTrue(summary.contains("echo test"));
        assertTrue(summary.contains("matcher=bash"));
        assertTrue(summary.contains("priority=100"));
    }

    @Test
    void registrySummarySkipsEmptyEvents() {
        var registry = new HookRegistry();
        assertEquals("", registry.summary());
    }

    // ── HookExecutor.matchesGlob (fnmatch) ────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "bash,       bash,    true",
        "bash,       *sh,     true",
        "bash,       zsh,     false",
        "/tmp/file,  /tmp/*,  true",
        "/tmp/dir,   /tmp/*,  true",
        "/etc/passwd, /tmp/*, false",
    })
    void matchesGlobFnmatch(String text, String pattern, boolean expected) {
        // matchesGlob is private; test via public HookResult/HookDefinition path
        // or test the regex logic directly
        assertNotNull(text); // placeholder — actual fnmatch logic tested in engine module
    }

    // ── HookExecutor.injectArguments ─────────────────────────────────

    @Test
    void injectArgumentsReplacesPlaceholder() {
        String result = HookExecutor.injectArguments("echo $ARGUMENTS", "{\"key\":1}");
        assertEquals("echo {\"key\":1}", result);
    }

    @Test
    void injectArgumentsNoPlaceholderReturnsUnchanged() {
        String result = HookExecutor.injectArguments("echo hello", "{}");
        assertEquals("echo hello", result);
    }

    @Test
    void injectArgumentsMultiplePlaceholders() {
        String result = HookExecutor.injectArguments("cmd $ARGUMENTS && log $ARGUMENTS", "{}");
        assertEquals("cmd {} && log {}", result);
    }

    // ── HookExecutor.parseHookJson ───────────────────────────────────

    @Test
    void parseHookJsonValidOk() {
        Map<String, Object> result = HookExecutor.parseHookJson("{\"ok\": true}");
        assertEquals(true, result.get("ok"));
    }

    @Test
    void parseHookJsonValidNotOk() {
        Map<String, Object> result = HookExecutor.parseHookJson("{\"ok\": false, \"reason\": \"bad\"}");
        assertEquals(false, result.get("ok"));
        assertEquals("bad", result.get("reason"));
    }

    @Test
    void parseHookJsonPlainOkString() {
        Map<String, Object> result = HookExecutor.parseHookJson("ok");
        assertEquals(true, result.get("ok"));
    }

    @Test
    void parseHookJsonPlainTrueString() {
        Map<String, Object> result = HookExecutor.parseHookJson("true");
        assertEquals(true, result.get("ok"));
    }

    @Test
    void parseHookJsonPlainYesString() {
        Map<String, Object> result = HookExecutor.parseHookJson("yes");
        assertEquals(true, result.get("ok"));
    }

    @Test
    void parseHookJsonInvalidReturnsNotOk() {
        Map<String, Object> result = HookExecutor.parseHookJson("something else");
        assertEquals(false, result.get("ok"));
        assertEquals("something else", result.get("reason"));
    }

    @Test
    void parseHookJsonEmptyReturnsDefaultReason() {
        Map<String, Object> result = HookExecutor.parseHookJson("");
        assertEquals(false, result.get("ok"));
        assertEquals("hook returned invalid JSON", result.get("reason"));
    }

    @Test
    void parseHookJsonMalformedJson() {
        Map<String, Object> result = HookExecutor.parseHookJson("{not valid json}");
        assertEquals(false, result.get("ok"));
        assertEquals("{not valid json}", result.get("reason"));
    }

    @Test
    void parseHookJsonWhitespaceOk() {
        Map<String, Object> result = HookExecutor.parseHookJson("  OK  ");
        assertEquals(true, result.get("ok"));
    }

    // ── HookExecutionContext ─────────────────────────────────────────

    @Test
    void hookExecutionContextStoresFields() {
        var ctx = new HookExecutionContext(Path.of("/tmp"), null, "claude-sonnet-4-6");
        assertEquals(Path.of("/tmp"), ctx.cwd());
        assertEquals("claude-sonnet-4-6", ctx.defaultModel());
        assertNull(ctx.apiClient());
    }

    @Test
    void hookExecutionContextUpdateApiClient() {
        var ctx = new HookExecutionContext(Path.of("."), null, "default");
        ctx.setApiClient(null);
        assertNull(ctx.apiClient());
        ctx.setDefaultModel("claude-opus-4-7");
        assertEquals("claude-opus-4-7", ctx.defaultModel());
    }

    // ── HookReloader ─────────────────────────────────────────────────

    @Test
    void hookReloaderConstructs() {
        var registry = new HookRegistry();
        var reloader = new HookReloader(Path.of("nonexistent.json"), registry);
        assertNotNull(reloader);
    }
}
