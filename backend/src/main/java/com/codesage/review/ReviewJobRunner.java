package com.codesage.review;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.codesage.common.Texts;
import com.codesage.exception.ApiException;

/** Executes one claimed job and applies the retry policy (exponential backoff, bounded attempts). */
@Component
public class ReviewJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobRunner.class);
    private static final Set<String> RETRYABLE_CODES = Set.of("GITHUB_UNAVAILABLE", "GITHUB_RATE_LIMITED");

    private final ReviewJobRepository jobs;
    private final ReviewPipeline pipeline;
    private final ReviewStateService state;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReviewJobRunner(ReviewJobRepository jobs, ReviewPipeline pipeline, ReviewStateService state,
                           TransactionTemplate tx, Clock clock) {
        this.jobs = jobs;
        this.pipeline = pipeline;
        this.state = state;
        this.tx = tx;
        this.clock = clock;
    }

    public void execute(UUID jobId) {
        Integer claimed = tx.execute(s -> jobs.claim(jobId, clock.instant()));
        if (claimed == null || claimed == 0) {
            return; // someone else is running it, or it is no longer queued
        }
        ReviewJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        MDC.put("reviewId", job.getReviewId().toString());
        try {
            pipeline.run(job.getReviewId());
            finish(jobId, state.isCancelled(job.getReviewId()) ? JobStatus.CANCELLED : JobStatus.SUCCEEDED, null);
        } catch (ApiException e) {
            handleFailure(job, e.getMessage(), RETRYABLE_CODES.contains(e.getCode()));
        } catch (RuntimeException e) {
            log.error("Review job {} failed unexpectedly", jobId, e);
            handleFailure(job, "Unexpected error while processing the review.", true);
        } finally {
            MDC.remove("reviewId");
        }
    }

    private void handleFailure(ReviewJob job, String message, boolean retryable) {
        int attempts = job.getAttempts();
        if (retryable && attempts < job.getMaxAttempts()) {
            Duration delay = Duration.ofSeconds(30L * (1L << Math.min(attempts - 1, 6)));
            tx.executeWithoutResult(s -> jobs.findById(job.getId()).ifPresent(j -> {
                j.setStatus(JobStatus.QUEUED);
                j.setLockedAt(null);
                j.setLastError(Texts.truncate(message, 1900));
                j.setNextAttemptAt(clock.instant().plus(delay));
            }));
            state.requeue(job.getReviewId(), message);
            log.info("Review job {} will retry in {}s (attempt {}/{})", job.getId(), delay.toSeconds(), attempts,
                    job.getMaxAttempts());
        } else {
            finish(job.getId(), JobStatus.FAILED, message);
            state.fail(job.getReviewId(), message);
        }
    }

    private void finish(UUID jobId, JobStatus status, String error) {
        tx.executeWithoutResult(s -> jobs.findById(jobId).ifPresent(j -> {
            j.setStatus(status);
            j.setLockedAt(null);
            j.setLastError(Texts.truncate(error, 1900));
        }));
    }
}
