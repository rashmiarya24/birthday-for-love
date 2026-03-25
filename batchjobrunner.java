package com.batch.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Triggers the batch job as soon as the application starts.
 *
 * Uses a timestamp parameter so every run is treated as a new
 * JobInstance by Spring Batch (enabling re-runs of the same job).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchJobRunner implements ApplicationRunner {

    private final JobLauncher jobLauncher;
    private final Job         csvGzImportJob;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addLong("run.timestamp", Instant.now().toEpochMilli())
            .toJobParameters();

        log.info("Launching batch job: csvGzImportJob");

        try {
            JobExecution execution = jobLauncher.run(csvGzImportJob, params);
            log.info("Job completed with status: {}", execution.getStatus());

            if (execution.getStatus() == BatchStatus.FAILED) {
                log.error("Job FAILED. Check logs for details.");
                System.exit(1);
            }

        } catch (JobExecutionAlreadyRunningException e) {
            log.error("Job is already running!", e);
        } catch (JobInstanceAlreadyCompleteException e) {
            log.info("Job already completed for these parameters (use a new timestamp).");
        } catch (JobRestartException e) {
            log.error("Job cannot be restarted: {}", e.getMessage());
        }
    }
}
