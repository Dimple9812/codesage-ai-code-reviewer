package com.codesage.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.codesage.findings.FindingCategory;
import com.codesage.findings.FindingSource;
import com.codesage.findings.FindingStatus;
import com.codesage.findings.ReviewFinding;
import com.codesage.findings.ReviewFindingRepository;
import com.codesage.findings.Severity;
import com.codesage.findings.Verification;
import com.codesage.repository.GitRepository;
import com.codesage.repository.GitRepositoryRepository;
import com.codesage.review.CodeReview;
import com.codesage.review.ReviewStatus;
import com.codesage.review.ReviewType;
import com.codesage.review.TriggerSource;
import com.codesage.support.IntegrationTestBase;

/** Verifies that one user can never read or act on another user's repositories, reviews or findings (IDOR). */
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    @Autowired
    GitRepositoryRepository repositories;
    @Autowired
    ReviewFindingRepository findings;

    TestUser alice;
    TestUser mallory;
    GitRepository aliceRepo;
    CodeReview aliceReview;
    ReviewFinding aliceFinding;

    @BeforeEach
    void seed() {
        alice = newUser();
        mallory = newUser();
        GitRepository repo = new GitRepository();
        repo.setUserId(alice.id());
        repo.setGithubId(alice.githubId());
        repo.setOwner(alice.login());
        repo.setName("secret-project");
        repo.setFullName(alice.login() + "/secret-project");
        repo.setPrivateRepo(true);
        repo.setDefaultBranch("main");
        repo.setLastSyncedAt(Instant.now());
        aliceRepo = repositories.save(repo);

        CodeReview review = new CodeReview();
        review.setUserId(alice.id());
        review.setRepositoryId(aliceRepo.getId());
        review.setType(ReviewType.REPOSITORY);
        review.setBranch("main");
        review.setCommitSha("abc");
        review.setStatus(ReviewStatus.COMPLETED);
        review.setTriggerSource(TriggerSource.MANUAL);
        aliceReview = reviewRepository.save(review);

        ReviewFinding f = new ReviewFinding();
        f.setReviewId(aliceReview.getId());
        f.setSource(FindingSource.AI);
        f.setAnalyzer("llm");
        f.setVerification(Verification.UNVERIFIED);
        f.setCategory(FindingCategory.SECURITY);
        f.setSeverity(Severity.HIGH);
        f.setConfidence(0.9);
        f.setTitle("Secret issue");
        f.setFilePath("src/Secret.java");
        f.setDescription("confidential");
        f.setFingerprint("fp");
        f.setStatus(FindingStatus.OPEN);
        aliceFinding = findings.save(f);
    }

    @Test
    void ownerCanAccessOwnResources() throws Exception {
        mvc.perform(get("/api/repositories/" + aliceRepo.getId()).with(as(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/reviews/" + aliceReview.getId()).with(as(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/reviews/" + aliceReview.getId() + "/findings").with(as(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("Secret issue"));
    }

    @Test
    void otherUserGets404ForEveryRepositoryEndpoint() throws Exception {
        String base = "/api/repositories/" + aliceRepo.getId();
        mvc.perform(get(base).with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(get(base + "/branches").with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(get(base + "/commits").with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(get(base + "/pull-requests").with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(put(base + "/auto-review").with(as(mallory)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isNotFound());
    }

    @Test
    void otherUserCannotStartReviewOnForeignRepository() throws Exception {
        mvc.perform(post("/api/reviews").with(as(mallory)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"" + aliceRepo.getId() + "\",\"type\":\"REPOSITORY\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUserGets404ForEveryReviewEndpoint() throws Exception {
        String base = "/api/reviews/" + aliceReview.getId();
        mvc.perform(get(base).with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(get(base + "/status").with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(get(base + "/findings").with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(get(base + "/files").with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(get(base + "/files/content").param("path", "src/Secret.java").with(as(mallory)))
                .andExpect(status().isNotFound());
        mvc.perform(get(base + "/compare").with(as(mallory))).andExpect(status().isNotFound());
        mvc.perform(post(base + "/cancel").with(as(mallory)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(base + "/publish-comments").with(as(mallory)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true,\"findingIds\":[\"" + aliceFinding.getId() + "\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUserCannotModifyForeignFinding() throws Exception {
        mvc.perform(patch("/api/findings/" + aliceFinding.getId()).with(as(mallory)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/findings/" + aliceFinding.getId()).with(as(alice)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void listingsAndDashboardOnlyContainOwnData() throws Exception {
        mvc.perform(get("/api/reviews").with(as(mallory)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/dashboard/summary").with(as(mallory)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReviews").value(0))
                .andExpect(jsonPath("$.openHigh").value(0));
        mvc.perform(get("/api/dashboard/issue-distribution").with(as(mallory)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/reviews").param("repositoryId", aliceRepo.getId().toString()).with(as(mallory)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void randomIdsReturn404NotServerErrors() throws Exception {
        mvc.perform(get("/api/reviews/" + UUID.randomUUID()).with(as(alice))).andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/not-a-uuid").with(as(alice))).andExpect(status().isBadRequest());
    }
}
