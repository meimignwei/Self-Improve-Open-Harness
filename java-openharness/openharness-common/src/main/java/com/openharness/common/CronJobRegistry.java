package com.openharness.common;

import java.time.Instant;

/**
 * Minimal cron job registry interface for tools.
 */
public interface CronJobRegistry {
    CronJob getJob(String name);

    record CronJob(String id, String cronExpr, String command, boolean enabled,
                   String timezone, String description, Instant lastRun, Instant nextRun) {}
}
