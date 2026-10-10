package com.codesage.findings;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewFindingRepository extends JpaRepository<ReviewFinding, UUID>, JpaSpecificationExecutor<ReviewFinding> {

    List<ReviewFinding> findByReviewId(UUID reviewId);

    List<ReviewFinding> findByReviewIdAndIdIn(UUID reviewId, Collection<UUID> ids);

    void deleteByReviewId(UUID reviewId);

    @Query("select f from ReviewFinding f where f.id = :id and f.reviewId in "
            + "(select r.id from CodeReview r where r.userId = :userId)")
    Optional<ReviewFinding> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("select f.category, count(f) from ReviewFinding f, CodeReview r "
            + "where f.reviewId = r.id and r.userId = :userId group by f.category")
    List<Object[]> countByCategory(@Param("userId") UUID userId);

    @Query("select f.severity, count(f) from ReviewFinding f, CodeReview r "
            + "where f.reviewId = r.id and r.userId = :userId and f.status = com.codesage.findings.FindingStatus.OPEN "
            + "group by f.severity")
    List<Object[]> countOpenBySeverity(@Param("userId") UUID userId);

    @Query("select f.status, count(f) from ReviewFinding f, CodeReview r "
            + "where f.reviewId = r.id and r.userId = :userId group by f.status")
    List<Object[]> countByStatus(@Param("userId") UUID userId);
}
