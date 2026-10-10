package com.codesage.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.codesage.auth.UserAccountService;
import com.codesage.findings.ReviewFindingRepository;
import com.codesage.repository.GitRepository;
import com.codesage.repository.GitRepositoryRepository;
import com.codesage.review.CodeReview;
import com.codesage.review.CodeReviewRepository;
import com.codesage.review.ReviewJob;
import com.codesage.review.ReviewJobRepository;
import com.codesage.review.JobStatus;
import com.codesage.review.ReviewStatus;
import com.codesage.review.ReviewType;
import com.codesage.review.TriggerSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Runs Flyway migrations, Hibernate schema validation and the custom queries against a real
 * PostgreSQL 16 server (embedded binaries, no Docker required).
 */
@SpringBootTest
@ActiveProfiles("test")
class EmbeddedPostgresIntegrationTest {

    static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void stop() throws IOException {
        PG.close();
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserAccountService accounts;
    @Autowired
    GitRepositoryRepository repositories;
    @Autowired
    CodeReviewRepository reviews;
    @Autowired
    ReviewJobRepository jobs;
    @Autowired
    ReviewFindingRepository findings;

    @Test
    void flywayCreatesTheSchemaOnPostgres() {
        String version = jdbc.queryForObject("select version()", String.class);
        assertThat(version).startsWith("PostgreSQL 16");
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("users", "github_connections", "repositories", "pull_requests", "code_reviews",
                "review_findings", "review_jobs", "review_files", "webhook_events", "user_settings",
                "flyway_schema_history");
    }

    @Test
    void entitiesQueriesAndJobClaimWorkOnPostgres() {
        var userId = accounts.upsertOnLogin(4242L, "pg-user", null, null, null, "token", "repo");
        GitRepository repo = new GitRepository();
        repo.setUserId(userId);
        repo.setGithubId(77L);
        repo.setOwner("pg-user");
        repo.setName("r");
        repo.setFullName("pg-user/r");
        repo.setLastSyncedAt(Instant.now());
        repo = repositories.save(repo);

        CodeReview review = new CodeReview();
        review.setUserId(userId);
        review.setRepositoryId(repo.getId());
        review.setType(ReviewType.REPOSITORY);
        review.setStatus(ReviewStatus.CANCELLED);
        review.setTriggerSource(TriggerSource.MANUAL);
        review.setScore(88);
        review = reviews.save(review);

        assertThat(reviews.countByUserId(userId)).isEqualTo(1);
        assertThat(reviews.countReviewedRepositories(userId)).isEqualTo(1);
        assertThat(reviews.findCreatedAtSince(userId, Instant.now().minusSeconds(60))).hasSize(1);
        assertThat(findings.countByCategory(userId)).isEmpty();

        ReviewJob job = new ReviewJob();
        job.setReviewId(review.getId());
        job.setIdempotencyKey("pg-test-" + review.getId());
        job.setStatus(JobStatus.QUEUED);
        job.setMaxAttempts(3);
        // Insert with a far-future due date so the background poller ignores it.
        job.setNextAttemptAt(Instant.now().plusSeconds(3600));
        ReviewJob saved = jobs.save(job);
        Integer first = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                .execute(s -> jdbc.update("update review_jobs set status = 'RUNNING' where id = ? and status = 'QUEUED'", saved.getId()));
        Integer second = jdbc.update("update review_jobs set status = 'RUNNING' where id = ? and status = 'QUEUED'", saved.getId());
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();

        // unique idempotency key is enforced by the database
        ReviewJob dup = new ReviewJob();
        dup.setReviewId(review.getId());
        dup.setIdempotencyKey(saved.getIdempotencyKey());
        dup.setStatus(JobStatus.QUEUED);
        dup.setMaxAttempts(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.saveAndFlush(dup))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
