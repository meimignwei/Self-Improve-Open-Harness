package com.openharness.extensions.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.openharness.common.CronJobRegistry;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.config.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent cron job registry stored at ~/.openharness/data/cron_jobs.json.
 * Java equivalent of Python services/cron.py CronRegistry.
 */
public class CronRegistry implements com.openharness.common.CronJobRegistry {

    private final Path configPath;

    public CronRegistry(Path configPath) {
        this.configPath = configPath;
    }

    public CronJob upsertJob(CronJob job) {
        List<CronJob> jobs = listJobs();

        if (job.id() != null) {
            jobs.removeIf(j -> j.id().equals(job.id()));
        }

        CronJob saved = new CronJob(
                job.id() != null ? job.id() : UUID.randomUUID().toString(),
                job.cronExpr(), job.command(), job.enabled(),
                job.timezone(), job.description(),
                job.lastRun(), computeNextRun(job.cronExpr()));

        jobs.add(saved);
        AtomicFileWriter.writeJson(configPath, jobs);
        return saved;
    }

    public void deleteJob(String jobId) {
        List<CronJob> jobs = listJobs();
        jobs.removeIf(j -> j.id().equals(jobId));
        AtomicFileWriter.writeJson(configPath, jobs);
    }

    public CronJob getJob(String jobId) {
        return listJobs().stream()
                .filter(j -> j.id().equals(jobId))
                .findFirst().orElse(null);
    }

    public List<CronJob> listJobs() {
        if (!Files.exists(configPath)) return new ArrayList<>();
        try {
            return OpenHarnessObjectMapper.get().readValue(
                    configPath.toFile(), new TypeReference<List<CronJob>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public void markJobRun(String jobId, JobRunResult result) {
        // For now just update lastRun/nextRun timestamps on the job
        List<CronJob> jobs = listJobs();
        for (int i = 0; i < jobs.size(); i++) {
            CronJob j = jobs.get(i);
            if (j.id().equals(jobId)) {
                jobs.set(i, new CronJob(j.id(), j.cronExpr(), j.command(), j.enabled(),
                        j.timezone(), j.description(), Instant.now(),
                        computeNextRun(j.cronExpr())));
                break;
            }
        }
        AtomicFileWriter.writeJson(configPath, jobs);
    }

    Instant computeNextRun(String cronExpr) {
        // Simple implementation: parse 5-field cron, compute next match
        // For now, return 1 hour from now as a placeholder
        return Instant.now().plusSeconds(3600);
    }

    public record JobRunResult(String jobId, boolean success, String output, Instant runAt) {}
}
