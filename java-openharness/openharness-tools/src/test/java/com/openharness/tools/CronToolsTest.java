package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CronToolsTest {

    private static final Pattern JOB_ID_PATTERN = Pattern.compile("Cron job created: ([a-f0-9-]+)");

    @TempDir
    Path tempDir;

    private Path openharnessDir;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        openharnessDir = tempDir.resolve(".openharness");
        Files.createDirectories(openharnessDir);
        ctx = new ToolExecutionContext(tempDir);
    }

    @Test
    void cronCreateShouldWriteRegistry() {
        var tool = new CronTools.CronCreateTool();
        var input = new CronTools.CronCreateTool.Input(
                "0 9 * * *", "daily report", "Daily task", "UTC", true);
        ToolResult result = tool.execute(input, ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("Cron job created"));
        assertTrue(Files.exists(openharnessDir.resolve("cron_jobs.json")));
    }

    @Test
    void cronListShouldReturnJobs() {
        var create = new CronTools.CronCreateTool();
        create.execute(new CronTools.CronCreateTool.Input(
                "*/5 * * * *", "frequent check", "Freq task", "UTC", true), ctx);

        var list = new CronTools.CronListTool();
        ToolResult result = list.execute(null, ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("Freq task"), "Should contain description: " + result.content());
        assertTrue(result.content().contains("*/5"), "Should contain cron expr: " + result.content());
    }

    @Test
    void cronListShouldHandleEmptyRegistry() {
        var list = new CronTools.CronListTool();
        ToolResult result = list.execute(null, ctx);

        assertFalse(result.isError());
        assertTrue(result.content().contains("No cron jobs"));
    }

    @Test
    void cronDeleteShouldRemoveJob() {
        var create = new CronTools.CronCreateTool();
        ToolResult created = create.execute(new CronTools.CronCreateTool.Input(
                "0 12 * * *", "noon", "Noon task", "UTC", true), ctx);
        assertFalse(created.isError(), "Create should succeed: " + created.content());

        Matcher m = JOB_ID_PATTERN.matcher(created.content());
        assertTrue(m.find(), "Should match job ID in: " + created.content());
        String jobId = m.group(1);

        var delete = new CronTools.CronDeleteTool();
        ToolResult result = delete.execute(new CronTools.CronDeleteTool.Input(jobId), ctx);

        assertFalse(result.isError(), "Delete should succeed: " + result.content());
        assertTrue(result.content().contains("deleted"));
    }

    @Test
    void cronDeleteUnknownJobShouldError() {
        var delete = new CronTools.CronDeleteTool();
        ToolResult result = delete.execute(new CronTools.CronDeleteTool.Input("nonexistent"), ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void cronCreateRejectsNullCron() {
        assertThrows(IllegalArgumentException.class,
                () -> new CronTools.CronCreateTool.Input(null, "p", "d", "UTC", true));
    }

    @Test
    void cronDeleteRejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CronTools.CronDeleteTool.Input(null));
    }

    @Test
    void cronListIsReadOnly() {
        assertTrue(new CronTools.CronListTool().isReadOnly(null));
    }
}
