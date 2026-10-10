package com.codesage.review;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codesage.common.Texts;
import com.codesage.findings.ReviewFinding;
import com.codesage.findings.ReviewFindingRepository;
import com.codesage.findings.ScoreCalculator;
import com.codesage.findings.Severity;

/** Short, transactional state transitions used by the (non-transactional) review pipeline. */
@Service
public class ReviewStateService {

    private final CodeReviewRepository reviews;
    private final ReviewFindingRepository findings;
    private final ReviewFileRepository files;
    private final Clock clock;

    public ReviewStateService(CodeReviewRepository reviews, ReviewFindingRepository findings,
                              ReviewFileRepository files, Clock clock) {
        this.reviews = reviews;
        this.findings = findings;
        this.files = files;
        this.clock = clock;
    }

    @Transactional
    public boolean markRunning(UUID reviewId) {
        CodeReview review = reviews.findById(reviewId).orElse(null);
        if (review == null || review.getStatus() == ReviewStatus.CANCELLED) {
            return false;
        }
        review.setStatus(ReviewStatus.RUNNING);
        review.setStartedAt(clock.instant());
        review.setErrorMessage(null);
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isCancelled(UUID reviewId) {
        return reviews.findById(reviewId).map(r -> r.getStatus() == ReviewStatus.CANCELLED).orElse(true);
    }

    @Transactional
    public void recordCommit(UUID reviewId, String sha, String branch) {
        reviews.findById(reviewId).ifPresent(r -> {
            r.setCommitSha(sha);
            if (branch != null) {
                r.setBranch(branch);
            }
        });
    }

    /** Persists results atomically. Returns false (and writes nothing) if the review was cancelled meanwhile. */
    @Transactional
    public boolean complete(UUID reviewId, List<ReviewFinding> newFindings, List<ReviewFile> reviewFiles,
                            int considered, int analyzed, int skipped, AiStatus aiStatus, String aiProvider,
                            String aiModel, String summary, List<String> notes) {
        CodeReview review = reviews.findById(reviewId).orElse(null);
        if (review == null || review.getStatus() == ReviewStatus.CANCELLED) {
            return false;
        }
        findings.deleteByReviewId(reviewId);
        files.deleteByReviewId(reviewId);
        findings.saveAll(newFindings);
        files.saveAll(reviewFiles);

        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        newFindings.forEach(f -> counts.merge(f.getSeverity(), 1, Integer::sum));
        review.setCriticalCount(counts.getOrDefault(Severity.CRITICAL, 0));
        review.setHighCount(counts.getOrDefault(Severity.HIGH, 0));
        review.setMediumCount(counts.getOrDefault(Severity.MEDIUM, 0));
        review.setLowCount(counts.getOrDefault(Severity.LOW, 0));
        review.setInfoCount(counts.getOrDefault(Severity.INFO, 0));
        review.setScore(ScoreCalculator.score(newFindings));
        review.setFilesConsidered(considered);
        review.setFilesAnalyzed(analyzed);
        review.setFilesSkipped(skipped);
        review.setAiStatus(aiStatus);
        review.setAiProvider(aiProvider);
        review.setAiModel(aiModel);
        review.setSummary(Texts.truncate(summary, 7900));
        review.setNotes(notes.isEmpty() ? null : Texts.truncate(String.join("\n", notes), 3900));
        review.setStatus(ReviewStatus.COMPLETED);
        review.setCompletedAt(clock.instant());
        return true;
    }

    @Transactional
    public void fail(UUID reviewId, String message) {
        reviews.findById(reviewId).ifPresent(r -> {
            if (r.getStatus() == ReviewStatus.CANCELLED) {
                return;
            }
            r.setStatus(ReviewStatus.FAILED);
            r.setErrorMessage(Texts.truncate(message, 1900));
            r.setCompletedAt(clock.instant());
        });
    }

    @Transactional
    public void requeue(UUID reviewId, String message) {
        reviews.findById(reviewId).ifPresent(r -> {
            if (r.getStatus() == ReviewStatus.CANCELLED) {
                return;
            }
            r.setStatus(ReviewStatus.QUEUED);
            r.setErrorMessage(Texts.truncate("Retrying after a transient error: " + message, 1900));
        });
    }
}
