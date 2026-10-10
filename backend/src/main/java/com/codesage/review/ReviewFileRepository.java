package com.codesage.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewFileRepository extends JpaRepository<ReviewFile, UUID> {

    List<ReviewFile> findByReviewIdOrderByPath(UUID reviewId);

    Optional<ReviewFile> findFirstByReviewIdAndPath(UUID reviewId, String path);

    void deleteByReviewId(UUID reviewId);
}
