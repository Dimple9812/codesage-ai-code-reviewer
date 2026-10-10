package com.codesage.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodeReviewRepository extends JpaRepository<CodeReview, UUID>, JpaSpecificationExecutor<CodeReview> {

    Optional<CodeReview> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    long countByUserIdAndStatus(UUID userId, ReviewStatus status);

    List<CodeReview> findTop5ByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("select r.createdAt from CodeReview r where r.userId = :userId and r.createdAt >= :since")
    List<Instant> findCreatedAtSince(@Param("userId") UUID userId, @Param("since") Instant since);

    @Query("select count(distinct r.repositoryId) from CodeReview r where r.userId = :userId")
    long countReviewedRepositories(@Param("userId") UUID userId);

    @Query("select avg(r.score) from CodeReview r where r.userId = :userId and r.status = com.codesage.review.ReviewStatus.COMPLETED and r.score is not null")
    Double averageScore(@Param("userId") UUID userId);
}
