package com.codesage.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.codesage.auth.UserAccountService;
import com.codesage.repository.GitRepository;
import com.codesage.repository.GitRepositoryRepository;
import com.codesage.review.CodeReview;
import com.codesage.review.CodeReviewRepository;
import com.codesage.review.ReviewStatus;
import com.codesage.review.ReviewType;
import com.codesage.review.TriggerSource;

/**
 * Runs the Flyway migrations and JPA mappings against a real PostgreSQL (Testcontainers).
 * Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PostgresMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    UserAccountService accounts;
    @Autowired
    GitRepositoryRepository repositories;
    @Autowired
    CodeReviewRepository reviews;

    @Test
    void migrationsApplyAndEntitiesRoundTrip() {
        var userId = accounts.upsertOnLogin(42L, "pg-user", null, null, null, "token", "repo");
        GitRepository repo = new GitRepository();
        repo.setUserId(userId);
        repo.setGithubId(1L);
        repo.setOwner("pg-user");
        repo.setName("r");
        repo.setFullName("pg-user/r");
        repo.setLastSyncedAt(Instant.now());
        repo = repositories.save(repo);
        CodeReview review = new CodeReview();
        review.setUserId(userId);
        review.setRepositoryId(repo.getId());
        review.setType(ReviewType.REPOSITORY);
        review.setStatus(ReviewStatus.COMPLETED);
        review.setTriggerSource(TriggerSource.MANUAL);
        review.setScore(90);
        reviews.save(review);
        assertThat(reviews.countByUserId(userId)).isEqualTo(1);
        assertThat(reviews.averageScore(userId)).isEqualTo(90.0);
    }
}
