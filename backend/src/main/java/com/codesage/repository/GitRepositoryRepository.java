package com.codesage.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GitRepositoryRepository extends JpaRepository<GitRepository, UUID> {

    Optional<GitRepository> findByIdAndUserId(UUID id, UUID userId);

    Optional<GitRepository> findByUserIdAndGithubId(UUID userId, Long githubId);

    List<GitRepository> findByUserIdOrderByPushedAtDesc(UUID userId);

    List<GitRepository> findByGithubIdAndAutoReviewTrue(Long githubId);

    long countByUserId(UUID userId);
}
