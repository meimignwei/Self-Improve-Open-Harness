package com.openharness.tools;

import com.openharness.common.CronJobRegistry;
import com.openharness.engine.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RemoteTriggerToolTest {

    @Test
    void shouldErrorWhenJobNotFound() {
        CronJobRegistry registry = new CronJobRegistry() {
            @Override public CronJob getJob(String name) { return null; }
        };

        var tool = new RemoteTriggerTool(registry);
        var result = tool.execute(new RemoteTriggerTool.Input("missing", 10),
                new ToolExecutionContext(Path.of(".")));

        assertTrue(result.isError());
        assertTrue(result.content().contains("not found"));
    }

    @Test
    void shouldErrorWhenJobHasNoCommand() {
        CronJobRegistry registry = new CronJobRegistry() {
            @Override public CronJob getJob(String name) {
                return new CronJob(name, "0 0 * * *", "", true, "UTC", "test", null, null);
            }
        };

        var tool = new RemoteTriggerTool(registry);
        var result = tool.execute(new RemoteTriggerTool.Input("empty", 10),
                new ToolExecutionContext(Path.of(".")));

        assertTrue(result.isError());
        assertTrue(result.content().contains("no command"));
    }

    @Test
    void shouldRejectNullName() {
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteTriggerTool.Input(null, 10));
    }
}
