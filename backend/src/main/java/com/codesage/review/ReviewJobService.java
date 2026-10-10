package com.codesage.review;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.codesage.config.AppProperties;

/**
 * Durable job queue on top of PostgreSQL. Jobs are dispatched to a bounded executor right after the
 * creating transaction commits; a scheduled poller re-dispatches due retries and recovers jobs left
 * RUNNING by a crashed instance. Claiming is an atomic conditional UPDATE, so a job never runs twice
 * concurrently even with several application instances.
 */
@Service
public class ReviewJobService {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobService.class);

    private final ReviewJobRepository jobs;
    private final Executor executor;
    private final ReviewJobRunner runner;
    private final AppProperties props;
    private final Clock clock;

    public ReviewJobService(ReviewJobRepository jobs, @Qualifier("reviewExecutor") Executor executor,
                            @Lazy ReviewJobRunner runner, AppProperties props, Clock clock) {
        this.jobs = jobs;
        this.executor = executor;
        this.runner = runner;
        this.props = props;
        this.clock = clock;
    }

    /** Must be called inside the transaction that created the review. */
    public ReviewJob enqueue(UUID reviewId, String idempotencyKey) {
        ReviewJob job = new ReviewJob();
        job.setReviewId(reviewId);
        job.setIdempotencyKey(idempotencyKey);
        job.setStatus(JobStatus.QUEUED);
        job.setMaxAttempts(Math.max(1, props.review().jobMaxAttempts()));
        job.setNextAttemptAt(clock.instant());
        job = jobs.save(job);
        dispatchAfterCommit(job.getId());
        return job;
    }

    public void dispatchAfterCommit(UUID jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(jobId);
                }
            });
        } else {
            dispatch(jobId);
        }
    }

    void dispatch(UUID jobId) {
        try {
            executor.execute(() -> runner.execute(jobId));
        } catch (RejectedExecutionException e) {
            log.warn("Review executor saturated; job {} will be picked up by the poller", jobId);
        }
    }

    @Scheduled(fixedDelayString = "${codesage.review.poll-interval-ms:10000}", initialDelay = 5000)
    public void poll() {
        recoverStale();
        List<UUID> due = jobs.findDueJobIds(clock.instant(), PageRequest.of(0, 20));
        due.forEach(this::dispatch);
    }

    @Transactional
    public void recoverStale() {
        Duration timeout = props.review().staleJobTimeout();
        for (ReviewJob job : jobs.findStale(clock.instant().minus(timeout))) {
            log.warn("Recovering stale review job {}", job.getId());
            job.setStatus(JobStatus.QUEUED);
            job.setLockedAt(null);
            job.setNextAttemptAt(clock.instant());
        }
    }
}
