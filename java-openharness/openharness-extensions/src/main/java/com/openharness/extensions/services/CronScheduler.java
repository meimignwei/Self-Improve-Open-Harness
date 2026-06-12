package com.openharness.extensions.services;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background daemon that ticks every 30s to execute due cron jobs.
 * Java equivalent of Python services/cron_scheduler.py.
 */
public class CronScheduler {

    private final CronRegistry registry;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private Thread daemonThread;

    public CronScheduler(CronRegistry registry) {
        this.registry = registry;
    }

    public void startDaemon() {
        if (daemonThread != null && daemonThread.isAlive()) return;

        daemonThread = Thread.startVirtualThread(() -> {
            while (!stopped.get()) {
                try {
                    for (CronRegistry.CronJob job : registry.listJobs()) {
                        if (job.enabled() && job.nextRun() != null
                                && Instant.now().isAfter(job.nextRun())) {
                            executeJob(job);
                        }
                    }
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    public void stop() {
        stopped.set(true);
        if (daemonThread != null) {
            daemonThread.interrupt();
        }
    }

    private void executeJob(CronRegistry.CronJob job) {
        Thread.startVirtualThread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", job.command());
                Process p = pb.start();

                boolean finished = p.waitFor(300, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    registry.markJobRun(job.id(), new CronRegistry.JobRunResult(
                            job.id(), false, "timeout after 300s", Instant.now()));
                    return;
                }

                String output = new String(p.getInputStream().readAllBytes());
                boolean success = p.exitValue() == 0;
                registry.markJobRun(job.id(), new CronRegistry.JobRunResult(
                        job.id(), success, output, Instant.now()));
            } catch (IOException e) {
                registry.markJobRun(job.id(), new CronRegistry.JobRunResult(
                        job.id(), false, e.getMessage(), Instant.now()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public boolean isRunning() {
        return daemonThread != null && daemonThread.isAlive();
    }
}
