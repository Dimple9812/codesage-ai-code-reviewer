package com.codesage.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewJobRepository extends JpaRepository<ReviewJob, UUID> {

    Optional<ReviewJob> findByIdempotencyKey(String idempotencyKey);

    Optional<ReviewJob> findByReviewId(UUID reviewId);

    /** Atomically claims a queued job. Returns 1 if this caller won the claim, 0 otherwise. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ReviewJob j set j.status = com.codesage.review.JobStatus.RUNNING, j.lockedAt = :now, "
            + "j.attempts = j.attempts + 1, j.updatedAt = :now "
            + "where j.id = :id and j.status = com.codesage.review.JobStatus.QUEUED")
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    @Query("select j.id from ReviewJob j where j.status = com.codesage.review.JobStatus.QUEUED "
            + "and j.nextAttemptAt <= :now order by j.nextAttemptAt")
    List<UUID> findDueJobIds(@Param("now") Instant now, Pageable pageable);

    @Query("select j from ReviewJob j where j.status = com.codesage.review.JobStatus.RUNNING and j.lockedAt < :cutoff")
    List<ReviewJob> findStale(@Param("cutoff") Instant cutoff);
}
