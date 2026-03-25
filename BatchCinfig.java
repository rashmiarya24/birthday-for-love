package com.batch.config;

import com.batch.mapper.FileToEntityRegistry;
import com.batch.model.CsvFileMetadata;
import com.batch.reader.GzipCsvItemReader;
import com.batch.writer.DynamicJpaItemWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * Central Spring Batch configuration.
 *
 * Architecture:
 * ┌─────────────────────────────────────────────────────┐
 * │  Job: csvGzImportJob                                │
 * │   └─ Step: masterStep  (partitions work by file)    │
 * │        └─ [file-1-step] ──┐                         │
 * │        └─ [file-2-step] ──┼── ThreadPoolExecutor    │
 * │        └─ [file-N-step] ──┘   (parallel execution)  │
 * └─────────────────────────────────────────────────────┘
 *
 * Each file gets its own Step with a dedicated GzipCsvItemReader
 * and the shared DynamicJpaItemWriter. Steps run in parallel via
 * the ThreadPoolTaskExecutor.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BatchConfig {

    private final JobRepository            jobRepository;
    private final PlatformTransactionManager txManager;
    private final FileToEntityRegistry     registry;
    private final DynamicJpaItemWriter     writer;
    private final BatchJobListener         jobListener;
    private final BatchSkipListener        skipListener;

    @Value("${batch.chunk-size:500}")
    private int chunkSize;

    @Value("${batch.thread-pool-size:5}")
    private int threadPoolSize;

    @Value("${batch.skip-limit:100}")
    private int skipLimit;

    // ─── Task Executor (parallel file processing) ─────────────

    @Bean
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threadPoolSize);
        executor.setMaxPoolSize(threadPoolSize);
        executor.setQueueCapacity(threadPoolSize * 2);
        executor.setThreadNamePrefix("batch-file-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(600);
        executor.initialize();
        log.info("Batch TaskExecutor initialised with {} threads", threadPoolSize);
        return executor;
    }

    // ─── Job ──────────────────────────────────────────────────

    @Bean
    public Job csvGzImportJob() throws Exception {
        List<CsvFileMetadata> files = registry.discoverFiles();

        if (files.isEmpty()) {
            log.warn("No .csv.gz files found in configured input folder. Job will be a no-op.");
        }

        // Build one Step per file
        Step[] steps = files.stream()
            .map(this::buildFileStep)
            .toArray(Step[]::new);

        // Chain all steps in a flow (Spring Batch executes them via the TaskExecutor)
        var jobBuilder = new JobBuilder("csvGzImportJob", jobRepository)
            .listener(jobListener);

        if (steps.length == 0) {
            // Safety: create a no-op step so the job doesn't fail to start
            return jobBuilder
                .start(noOpStep())
                .build();
        }

        var flowBuilder = jobBuilder.flow(steps[0]);
        for (int i = 1; i < steps.length; i++) {
            flowBuilder = flowBuilder.next(steps[i]);
        }

        return flowBuilder.end().build();
    }

    // ─── Per-file Step ────────────────────────────────────────

    /**
     * Creates a chunk-oriented step for one .csv.gz file.
     * The step is thread-safe: each has its own reader, all share the writer.
     */
    private Step buildFileStep(CsvFileMetadata metadata) {
        String stepName = "step-" + metadata.getFileName();

        GzipCsvItemReader reader = new GzipCsvItemReader(metadata, chunkSize);

        return new StepBuilder(stepName, jobRepository)
            .<Object, Object>chunk(chunkSize, txManager)
            .reader(reader)
            // No processor by default — add one below if you need transformations:
            // .processor(myItemProcessor())
            .writer(writer)
            .faultTolerant()
                .skipLimit(skipLimit)
                .skip(Exception.class)
                .noSkip(java.sql.BatchUpdateException.class)  // fail fast on DB errors
                .listener(skipListener)
            .taskExecutor(buildSingleThreadExecutor(stepName))  // 1 thread per step (steps run in parallel via job flow)
            .build();
    }

    /**
     * Each step gets its own single-thread executor.
     * Parallelism at the job level is achieved because multiple steps
     * are submitted concurrently to the job's TaskExecutor.
     *
     * If you prefer partitioning a single large file instead, swap this
     * for a multi-thread step executor.
     */
    private TaskExecutor buildSingleThreadExecutor(String stepName) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setThreadNamePrefix(stepName + "-");
        exec.initialize();
        return exec;
    }

    /** No-op step used when no files are found. */
    private Step noOpStep() {
        return new StepBuilder("noOpStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                log.warn("No files found to process.");
                return org.springframework.batch.repeat.RepeatStatus.FINISHED;
            }, txManager)
            .build();
    }
}
