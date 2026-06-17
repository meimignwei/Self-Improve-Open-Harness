package com.openharness.permissions;

import com.openharness.config.PermissionSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionCheckerTest {

    // ── PermissionMode ──────────────────────────────────────────────

    @Test
    void modeFromValidString() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.from("default"));
        assertEquals(PermissionMode.PLAN, PermissionMode.from("plan"));
        assertEquals(PermissionMode.FULL_AUTO, PermissionMode.from("full_auto"));
    }

    @Test
    void modeFromInvalidStringDefaultsToDefault() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.from(""));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.from("bogus"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.from(null));
    }

    @Test
    void modeValueReturnsString() {
        assertEquals("default", PermissionMode.DEFAULT.value());
        assertEquals("plan", PermissionMode.PLAN.value());
        assertEquals("full_auto", PermissionMode.FULL_AUTO.value());
    }

    // ── PermissionDecision ──────────────────────────────────────────

    @Test
    void allowDecision() {
        PermissionDecision d = PermissionDecision.allow("reason");
        assertTrue(d.allowed());
        assertFalse(d.requiresConfirmation());
        assertEquals("reason", d.reason());
    }

    @Test
    void denyDecision() {
        PermissionDecision d = PermissionDecision.deny("blocked");
        assertFalse(d.allowed());
        assertFalse(d.requiresConfirmation());
        assertEquals("blocked", d.reason());
    }

    @Test
    void confirmDecision() {
        PermissionDecision d = PermissionDecision.confirm("need approval");
        assertFalse(d.allowed());
        assertTrue(d.requiresConfirmation());
        assertEquals("need approval", d.reason());
    }

    // ── PathRule ────────────────────────────────────────────────────

    @Test
    void pathRuleRecord() {
        var rule = new PathRule("*.log", true);
        assertEquals("*.log", rule.pattern());
        assertTrue(rule.allow());
    }

    @Test
    void sensitivePathsIncludesAllBuiltIns() {
        assertEquals(10, PathRule.SENSITIVE_PATHS.length);
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.ssh/*"));
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.aws/credentials"));
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.aws/config"));
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.config/gcloud/*"));
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.azure/*"));
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.gnupg/*"));
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.docker/config.json"));
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.kube/config"));
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.openharness/credentials.json"));
        assertTrue(containsPattern(PathRule.SENSITIVE_PATHS, "*/.openharness/copilot_auth.json"));
    }

    // ── PermissionChecker.evaluate — Sensitive paths ─────────────────

    @Test
    void builtinSensitivePathDenied() {
        var checker = checker(defaultSettings());

        PermissionDecision d = checker.evaluate("bash", false, "/home/user/.ssh/id_rsa", null);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("sensitive credential path"));
        assertTrue(d.reason().contains("/home/user/.ssh/id_rsa"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/home/user/.ssh/id_rsa",
        "/root/.ssh/authorized_keys",
        "/home/user/.aws/credentials",
        "/home/user/.aws/config",
        "/home/user/.config/gcloud/application_default_credentials.json",
        "/home/user/.azure/accessTokens.json",
        "/home/user/.gnupg/secring.gpg",
        "/home/user/.docker/config.json",
        "/home/user/.kube/config",
        "/home/user/.openharness/credentials.json",
        "/home/user/.openharness/copilot_auth.json",
    })
    void everySensitivePatternBlocksAccess(String filePath) {
        var checker = checker(fullAutoSettings());
        PermissionDecision d = checker.evaluate("file_read", true, filePath, null);
        assertFalse(d.allowed(), "Expected " + filePath + " to be denied");
        assertTrue(d.reason().contains("sensitive credential path"));
    }

    @Test
    void nonSensitivePathAllowedByDefault() {
        var checker = checker(defaultSettings());
        PermissionDecision d = checker.evaluate("file_read", true, "/home/user/projects/src/main.java", null);
        assertTrue(d.allowed());
    }

    // ── PermissionChecker.evaluate — Deny / Allow lists ──────────────

    @Test
    void explicitDenyList() {
        var settings = defaultSettings();
        settings.setDeniedTools(List.of("bash", "rm"));
        var checker = checker(settings);

        PermissionDecision d = checker.evaluate("bash", false, null, null);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("explicitly denied"));
    }

    @Test
    void explicitAllowListOverridesDeny() {
        var settings = defaultSettings();
        settings.setAllowedTools(List.of("bash"));
        var checker = checker(settings);

        // FULL_AUTO=off, not read-only, so would be confirm — but allow list wins
        PermissionDecision d = checker.evaluate("bash", false, null, null);
        assertTrue(d.allowed());
        assertTrue(d.reason().contains("explicitly allowed"));
    }

    @Test
    void allowListWinsOverSensitivePath() {
        var settings = defaultSettings();
        settings.setAllowedTools(List.of("file_read"));
        var checker = checker(settings);

        // Sensitive path check runs BEFORE allow list, so sensitive path still blocks
        PermissionDecision d = checker.evaluate("file_read", false, "/home/user/.ssh/id_rsa", null);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("sensitive credential path"));
    }

    // ── PermissionChecker.evaluate — Path rules ─────────────────────

    @Test
    void pathDenyRule() {
        var settings = defaultSettings();
        settings.setPathRules(List.of(pathRule("/etc/*", false)));
        var checker = checker(settings);

        PermissionDecision d = checker.evaluate("file_read", true, "/etc/passwd", null);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("matches deny rule"));
        assertTrue(d.reason().contains("/etc/*"));
    }

    @Test
    void pathAllowRule() {
        var settings = defaultSettings();
        settings.setPathRules(List.of(pathRule("/safe/*", true)));
        var checker = checker(settings);

        // Read-only, so it's allowed anyway — but path rule doesn't interfere
        PermissionDecision d = checker.evaluate("file_write", false, "/safe/data.txt", null);
        // Not denied, continues to mode check → confirm in default mode
        assertTrue(d.requiresConfirmation());
    }

    @Test
    void pathRuleMatchesDirectoryRoot() {
        var settings = defaultSettings();
        settings.setPathRules(List.of(pathRule("/home/user/secrets/*", false)));
        var checker = checker(settings);

        PermissionDecision d = checker.evaluate("grep", true, "/home/user/secrets", null);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("matches deny rule"));
    }

    // ── PermissionChecker.evaluate — Command deny patterns ──────────

    @Test
    void commandDenyPattern() {
        var settings = defaultSettings();
        settings.setDeniedCommands(List.of("rm -rf /*"));
        var checker = checker(settings);

        PermissionDecision d = checker.evaluate("bash", false, null, "rm -rf /*");
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("Command matches deny pattern"));
    }

    @Test
    void commandNotMatchingDenyPatternPasses() {
        var settings = defaultSettings();
        settings.setDeniedCommands(List.of("rm -rf /*"));
        var checker = checker(settings);

        // Not denied by command pattern, but in default mode it requires confirmation
        PermissionDecision d = checker.evaluate("bash", false, null, "ls -la");
        assertTrue(d.requiresConfirmation());
    }

    // ── PermissionChecker.evaluate — Mode behaviour ──────────────────

    @Test
    void fullAutoAllowsAll() {
        var checker = checker(fullAutoSettings());

        PermissionDecision d = checker.evaluate("bash", false, null, "rm -rf /");
        assertTrue(d.allowed());
        assertTrue(d.reason().contains("Auto mode"));
    }

    @Test
    void readOnlyToolsAlwaysAllowed() {
        var checker = checker(defaultSettings());

        PermissionDecision d = checker.evaluate("file_read", true, "/tmp/test.txt", null);
        assertTrue(d.allowed());
        assertTrue(d.reason().contains("read-only"));
    }

    @Test
    void planModeBlocksMutatingTool() {
        var checker = checker(planSettings());

        PermissionDecision d = checker.evaluate("file_write", false, "/tmp/test.txt", null);
        assertFalse(d.allowed());
        assertFalse(d.requiresConfirmation());
        assertTrue(d.reason().contains("Plan mode blocks"));
    }

    @Test
    void planModeAllowsReadOnly() {
        var checker = checker(planSettings());

        PermissionDecision d = checker.evaluate("file_read", true, "/tmp/test.txt", null);
        assertTrue(d.allowed());
        assertTrue(d.reason().contains("read-only"));
    }

    @Test
    void defaultModeRequiresConfirmationForMutatingTool() {
        var checker = checker(defaultSettings());

        PermissionDecision d = checker.evaluate("bash", false, null, "ls");
        assertFalse(d.allowed());
        assertTrue(d.requiresConfirmation());
        assertTrue(d.reason().contains("Mutating tools require user confirmation"));
    }

    @Test
    void defaultModeBashHintForInstallCommands() {
        var checker = checker(defaultSettings());

        PermissionDecision d = checker.evaluate("bash", false, null, "npm install express");
        assertTrue(d.reason().contains("Package installation and scaffolding commands"));
    }

    // ── fnmatch ──────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "/home/user/.ssh/id_rsa,   */.ssh/*,         true",
        "/home/user/src/main.java, */.ssh/*,         false",
        "/home/user/test.txt,     *.txt,             true",
        "/etc/nginx/nginx.conf,    /etc/*,            true",
        "/usr/local/bin/tool,     /usr/local/bin/*,  true",
        "/home/user/project,      /home/user/*,      true",
    })
    void fnmatchPatterns(String path, String pattern, boolean expected) {
        assertEquals(expected, PermissionChecker.fnmatch(path, pattern));
    }

    @Test
    void fnmatchInvalidPatternReturnsFalse() {
        assertFalse(PermissionChecker.fnmatch("test", "[invalid-glob"));
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    void nullFilePathSkipsSensitiveCheck() {
        var checker = checker(defaultSettings());
        PermissionDecision d = checker.evaluate("bash", false, null, "ls");
        assertTrue(d.requiresConfirmation());
    }

    @Test
    void nullCommandSkipsCommandDenyCheck() {
        var checker = checker(defaultSettings());
        PermissionDecision d = checker.evaluate("file_write", false, "/tmp/test.txt", null);
        assertTrue(d.requiresConfirmation());
    }

    @Test
    void emptySettingsAllDefaults() {
        var checker = checker(new PermissionSettings());
        PermissionDecision d = checker.evaluate("file_read", true, "/tmp/test.txt", null);
        assertTrue(d.allowed());
    }

    @Test
    void blankPathRulePatternSkipped() {
        var settings = defaultSettings();
        settings.setPathRules(List.of(
            new PermissionSettings.PathRuleConfig("", true),
            new PermissionSettings.PathRuleConfig("  ", true),
            new PermissionSettings.PathRuleConfig("/tmp/*", false)
        ));
        var checker = checker(settings);

        // The blank rules should be skipped; /tmp/* deny should apply
        PermissionDecision d = checker.evaluate("file_read", true, "/tmp/test.txt", null);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("matches deny rule"));
    }

    @Test
    void pathRuleWithTrailingSlashInFilePath() {
        var settings = defaultSettings();
        settings.setPathRules(List.of(pathRule("/home/user/data/*", false)));
        var checker = checker(settings);

        // Input has trailing slash
        PermissionDecision d = checker.evaluate("grep", true, "/home/user/data/", null);
        assertFalse(d.allowed());
    }

    @Test
    void multipleDenyCommandsAllChecked() {
        var settings = defaultSettings();
        settings.setDeniedCommands(List.of("rm -rf *", "*DROP TABLE*", "shutdown"));
        var checker = checker(settings);

        PermissionDecision d = checker.evaluate("bash", false, null, "DROP TABLE students;");
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("Command matches deny pattern"));
    }

    @Test
    void bashHintNotTriggeredByNonInstallCommand() {
        var checker = checker(defaultSettings());
        PermissionDecision d = checker.evaluate("bash", false, null, "echo hello");
        assertFalse(d.reason().contains("Package installation"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "npm install react",
        "pnpm install lodash",
        "yarn install",
        "bun install",
        "pip install pandas",
        "uv pip install numpy",
        "poetry install",
        "cargo install ripgrep",
        "create-next-app",
        "npm create vite",
        "pnpm create next-app",
        "yarn create react-app",
        "bun create hono",
        "npx create-react-app",
        "npm init -y",
        "pnpm init -y",
        "yarn init -y",
    })
    void bashHintTriggeredForAllInstallMarkers(String command) {
        var checker = checker(defaultSettings());
        PermissionDecision d = checker.evaluate("bash", false, null, command);
        assertTrue(d.reason().contains("Package installation and scaffolding commands"),
            "Expected bash hint for: " + command);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static PermissionSettings defaultSettings() {
        var s = new PermissionSettings();
        s.setMode("default");
        return s;
    }

    private static PermissionSettings fullAutoSettings() {
        var s = new PermissionSettings();
        s.setMode("full_auto");
        return s;
    }

    private static PermissionSettings planSettings() {
        var s = new PermissionSettings();
        s.setMode("plan");
        return s;
    }

    private static PermissionChecker checker(PermissionSettings settings) {
        return new PermissionChecker(settings);
    }

    private static PermissionSettings.PathRuleConfig pathRule(String pattern, boolean allow) {
        return new PermissionSettings.PathRuleConfig(pattern, allow);
    }

    private static boolean containsPattern(PathRule[] rules, String pattern) {
        for (var r : rules) {
            if (r.pattern().equals(pattern)) return true;
        }
        return false;
    }
}