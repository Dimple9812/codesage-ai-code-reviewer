package com.codesage.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.codesage.findings.ReviewFinding;
import com.codesage.findings.ReviewFindingRepository;
import com.codesage.review.CodeReview;
import com.codesage.review.ReviewStatus;
import com.codesage.review.TriggerSource;
import com.codesage.support.IntegrationTestBase;
import com.codesage.webhook.WebhookSignatureVerifier;
import com.fasterxml.jackson.databind.JsonNode;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

class PullRequestAndWebhookIntegrationTest extends IntegrationTestBase {

    static final String SECRET = "test-webhook-secret";
    static final String FILE = """
            import os

            def run(cmd):
                os.system(cmd)

            def ok():
                return 1
            """;
    static final String PATCH = "@@ -1,3 +1,4 @@\n import os\n \n def run(cmd):\n+    os.system(cmd)\n";

    @Autowired
    ReviewFindingRepository findingRepository;

    TestUser user;
    String owner;
    long repoGithubId;
    UUID repositoryId;
    String headSha;

    @BeforeEach
    void setUp() throws Exception {
        user = newUser();
        owner = user.login();
        repoGithubId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
        headSha = "head" + repoGithubId;
        GH.jsonForToken(user.token(), "/user/repos", "[" + repoJson(repoGithubId, owner, "svc", false) + "]");
        String pull = pullJson(7);
        GH.json("GET", "/repos/" + owner + "/svc/pulls/7", pull);
        GH.json("GET", "/repos/" + owner + "/svc/pulls", "[" + pull + "]");
        GH.json("GET", "/repos/" + owner + "/svc/pulls/7/files", json.writeValueAsString(List.of(
                java.util.Map.of("filename", "app/run.py", "status", "modified", "additions", 1, "deletions", 0,
                        "changes", 1, "patch", PATCH, "sha", "f1"))));
        GH.raw("/repos/" + owner + "/svc/contents/app/run.py", FILE);
        LLM.respond(r -> "{\"summary\":\"PR adds a shell call.\",\"overallScore\":70,\"findings\":[]}");

        String body = mvc.perform(get("/api/repositories").with(as(user))).andReturn().getResponse().getContentAsString();
        repositoryId = UUID.fromString(json.readTree(body).get(0).get("id").asText());
    }

    private String pullJson(int number) {
        return "{\"id\":99,\"number\":" + number + ",\"title\":\"Add runner\",\"state\":\"open\",\"user\":{\"login\":\""
                + owner + "\"},\"head\":{\"ref\":\"feature/run\",\"sha\":\"" + headSha + "\"},\"base\":{\"ref\":\"main\","
                + "\"sha\":\"base\"},\"html_url\":\"https://github.com/" + owner + "/svc/pull/" + number + "\","
                + "\"updated_at\":\"2026-10-01T10:00:00Z\",\"draft\":false}";
    }

    @Test
    void pullRequestReviewMapsFindingsToChangedLinesAndPublishesOnlyAfterConfirmation() throws Exception {
        mvc.perform(get("/api/repositories/" + repositoryId + "/pull-requests").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(7))
                .andExpect(jsonPath("$[0].headSha").value(headSha));

        String created = mvc.perform(post("/api/reviews").with(as(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"" + repositoryId + "\",\"type\":\"PULL_REQUEST\",\"pullRequestNumber\":7}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pullRequestNumber").value(7))
                .andReturn().getResponse().getContentAsString();
        UUID reviewId = UUID.fromString(json.readTree(created).get("id").asText());
        awaitReview(reviewId, ReviewStatus.COMPLETED);

        List<ReviewFinding> findings = findingRepository.findByReviewId(reviewId);
        assertThat(findings).extracting(ReviewFinding::getRuleId).containsExactly("SEC-CMD-002");
        ReviewFinding finding = findings.get(0);
        assertThat(finding.getStartLine()).isEqualTo(4);
        assertThat(finding.isInDiff()).isTrue();
        assertThat(LLM.requests().get(LLM.requests().size() - 1).user()).contains(">    4|");

        String publishPath = "/api/reviews/" + reviewId + "/publish-comments";
        // Without explicit confirmation nothing is sent to GitHub.
        mvc.perform(post(publishPath).with(as(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":false,\"findingIds\":[\"" + finding.getId() + "\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(GH.requests()).noneMatch(r -> "POST".equals(r.getMethod()));

        GH.on("POST", "/repos/" + owner + "/svc/pulls/7/reviews", r -> new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":1,\"html_url\":\"https://github.com/" + owner + "/svc/pull/7#review-1\",\"state\":\"COMMENTED\"}"));
        mvc.perform(post(publishPath).with(as(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true,\"findingIds\":[\"" + finding.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(1))
                .andExpect(jsonPath("$.githubReviewUrl").value("https://github.com/" + owner + "/svc/pull/7#review-1"));

        RecordedRequest posted = GH.requests().stream()
                .filter(r -> "POST".equals(r.getMethod()) && r.getPath().contains("/" + owner + "/svc/pulls/7/reviews"))
                .findFirst().orElseThrow();
        JsonNode payload = json.readTree(posted.getBody().readUtf8());
        assertThat(payload.path("event").asText()).isEqualTo("COMMENT");
        assertThat(payload.path("commit_id").asText()).isEqualTo(headSha);
        assertThat(payload.path("comments").get(0).path("path").asText()).isEqualTo("app/run.py");
        assertThat(payload.path("comments").get(0).path("line").asInt()).isEqualTo(4);
        // Never approves, requests changes or merges.
        assertThat(GH.requests()).noneMatch(r -> r.getPath().contains("/merge"));
    }

    private org.springframework.test.web.servlet.ResultActions deliver(String deliveryId, String event, String body,
                                                                       String signature) throws Exception {
        return mvc.perform(post("/api/webhooks/github")
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", deliveryId)
                .header("X-Hub-Signature-256", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String webhookBody(String action) {
        return "{\"action\":\"" + action + "\",\"number\":7,\"pull_request\":" + pullJson(7)
                + ",\"repository\":{\"id\":" + repoGithubId + ",\"full_name\":\"" + owner + "/svc\"}}";
    }

    private static String sign(String body) {
        return WebhookSignatureVerifier.signatureHeader(SECRET, body.getBytes(StandardCharsets.UTF_8));
    }

    private List<CodeReview> webhookReviews() {
        return reviewRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(user.id()) && r.getTriggerSource() == TriggerSource.WEBHOOK).toList();
    }

    @Test
    void webhookRejectsInvalidSignatures() throws Exception {
        String body = webhookBody("opened");
        deliver(UUID.randomUUID().toString(), "pull_request", body, "sha256=" + "0".repeat(64))
                .andExpect(status().isUnauthorized());
        deliver(UUID.randomUUID().toString(), "pull_request", body,
                WebhookSignatureVerifier.signatureHeader("wrong-secret", body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webhooks/github").header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "abc").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookQueuesReviewOnceAndIgnoresDuplicates() throws Exception {
        mvc.perform(put("/api/repositories/" + repositoryId + "/auto-review").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.autoReview").value(true));

        String body = webhookBody("opened");
        String delivery = UUID.randomUUID().toString();
        deliver(delivery, "pull_request", body, sign(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("processed"));
        // GitHub redelivers the same delivery id
        deliver(delivery, "pull_request", body, sign(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
        // A different delivery for the same PR head commit (e.g. 'reopened') must not queue a second review
        String reopened = webhookBody("reopened");
        deliver(UUID.randomUUID().toString(), "pull_request", reopened, sign(reopened)).andExpect(status().isAccepted());

        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> webhookReviews().stream().allMatch(r -> r.getStatus() == ReviewStatus.COMPLETED)
                        && !webhookReviews().isEmpty());
        assertThat(webhookReviews()).hasSize(1);
    }

    @Test
    void webhookIgnoresIrrelevantEventsAndUnsubscribedRepositories() throws Exception {
        String closed = webhookBody("closed");
        deliver(UUID.randomUUID().toString(), "pull_request", closed, sign(closed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ignored"));
        String push = "{\"ref\":\"refs/heads/main\",\"repository\":{\"id\":" + repoGithubId + "}}";
        deliver(UUID.randomUUID().toString(), "push", push, sign(push))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ignored"));
        // auto-review not enabled for this repository
        String opened = webhookBody("opened");
        deliver(UUID.randomUUID().toString(), "pull_request", opened, sign(opened))
                .andExpect(jsonPath("$.status").value("ignored"));
        deliver(UUID.randomUUID().toString(), "ping", "{}", sign("{}")).andExpect(jsonPath("$.status").value("pong"));
        assertThat(webhookReviews()).isEmpty();
    }
}
