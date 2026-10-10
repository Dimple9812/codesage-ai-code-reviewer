package com.codesage.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.codesage.findings.ReviewFindingRepository;
import com.codesage.review.AiStatus;
import com.codesage.review.ReviewStatus;
import com.codesage.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

class ReviewFlowIntegrationTest extends IntegrationTestBase {

    static final String JAVA = """
            package demo;

            public class UserService {
                public boolean isAdmin(String role) {
                    return role == "admin";
                }

                public String name(User u) {
                    return u.getName().trim();
                }
            }
            """;

    static final String PY = "AWS_ACCESS_KEY_ID = \"AKIAABCDEFGHIJKLMNOP\"\n";

    @org.springframework.beans.factory.annotation.Autowired
    ReviewFindingRepository findingRepository;

    TestUser user;
    String owner;
    UUID repositoryId;

    @BeforeEach
    void setUp() throws Exception {
        user = newUser();
        owner = user.login();
        long repoGithubId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
        GH.jsonForToken(user.token(), "/user/repos", "[" + repoJson(repoGithubId, owner, "demo", true) + "]");
        String sha = "sha" + repoGithubId;
        GH.json("GET", "/repos/" + owner + "/demo/branches/main",
                "{\"name\":\"main\",\"commit\":{\"sha\":\"" + sha + "\"},\"protected\":false}");
        GH.json("GET", "/repos/" + owner + "/demo/git/trees/" + sha, """
                {"sha":"%s","truncated":false,"tree":[
                  {"path":"src/main/java/demo/UserService.java","type":"blob","size":%d,"sha":"1"},
                  {"path":"config.py","type":"blob","size":%d,"sha":"2"},
                  {"path":"node_modules/lib/index.js","type":"blob","size":10,"sha":"3"},
                  {"path":"README.md","type":"blob","size":10,"sha":"4"},
                  {"path":"src","type":"tree","sha":"5"}]}
                """.formatted(sha, JAVA.length(), PY.length()));
        GH.raw("/repos/" + owner + "/demo/contents/src/main/java/demo/UserService.java", JAVA);
        GH.raw("/repos/" + owner + "/demo/contents/config.py", PY);
        GH.json("GET", "/repos/" + owner + "/demo/branches", "[{\"name\":\"main\",\"commit\":{\"sha\":\"" + sha + "\"}}]");

        LLM.respond(r -> """
                {"summary":"A null dereference risk was found.","overallScore":78,"findings":[
                 {"title":"Possible null pointer exception","category":"BUG","severity":"HIGH","confidence":0.85,
                  "filePath":"src/main/java/demo/UserService.java","startLine":9,"endLine":9,
                  "description":"getName() may return null before trim() is called.",
                  "impact":"NullPointerException returns HTTP 500.","recommendation":"Guard against null.",
                  "suggestedCode":"return u.getName() == null ? \\"\\" : u.getName().trim();","ruleId":"JAVA-NULL-001"}]}
                """);

        String body = mvc.perform(get("/api/repositories").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value(owner + "/demo"))
                .andExpect(jsonPath("$[0].isPrivate").value(true))
                .andReturn().getResponse().getContentAsString();
        repositoryId = UUID.fromString(json.readTree(body).get(0).get("id").asText());
    }

    private UUID startReview(String idempotencyKey) throws Exception {
        var request = post("/api/reviews").with(as(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"repositoryId\":\"" + repositoryId + "\",\"type\":\"REPOSITORY\",\"branch\":\"main\"}");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        String body = mvc.perform(request).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    @Test
    void fullRepositoryReviewPersistsAiAndStaticFindings() throws Exception {
        UUID reviewId = startReview(null);
        awaitReview(reviewId, ReviewStatus.COMPLETED);

        JsonNode detail = json.readTree(mvc.perform(get("/api/reviews/" + reviewId).with(as(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.path("review").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("review").path("aiStatus").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("review").path("commitSha").asText()).startsWith("sha");
        assertThat(detail.path("summary").asText()).contains("null dereference");
        assertThat(detail.path("filesAnalyzed").asInt()).isEqualTo(2);
        assertThat(detail.path("review").path("score").asInt()).isBetween(1, 99);

        JsonNode findings = json.readTree(mvc.perform(get("/api/reviews/" + reviewId + "/findings").with(as(user)))
                .andReturn().getResponse().getContentAsString());
        List<String> rules = findings.findValues("ruleId").stream().map(JsonNode::asText).toList();
        assertThat(rules).contains("JAVA-NULL-001", "PMD-UseEqualsToCompareStrings", "SEC-SECRET-001");
        JsonNode ai = findings.findParents("ruleId").stream()
                .filter(n -> "JAVA-NULL-001".equals(n.path("ruleId").asText())).findFirst().orElseThrow();
        assertThat(ai.path("source").asText()).isEqualTo("AI");
        assertThat(ai.path("verification").asText()).isEqualTo("UNVERIFIED");
        assertThat(ai.path("originalCode").asText()).contains("u.getName().trim()");
        JsonNode secret = findings.findParents("ruleId").stream()
                .filter(n -> "SEC-SECRET-001".equals(n.path("ruleId").asText())).findFirst().orElseThrow();
        assertThat(secret.path("originalCode").asText()).doesNotContain("AKIAABCDEFGHIJKLMNOP");

        // The AI prompt treats repository content as untrusted data and never includes vendored files.
        String prompt = LLM.requests().get(LLM.requests().size() - 1).user();
        assertThat(prompt).contains("untrusted").doesNotContain("node_modules");

        // File explorer + live content at the reviewed commit
        mvc.perform(get("/api/reviews/" + reviewId + "/files").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.path=='node_modules/lib/index.js')].analyzed").value(false));
        mvc.perform(get("/api/reviews/" + reviewId + "/files/content")
                        .param("path", "src/main/java/demo/UserService.java").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("java"))
                .andExpect(jsonPath("$.content").value(JAVA));
        mvc.perform(get("/api/reviews/" + reviewId + "/files/content").param("path", "../etc/passwd").with(as(user)))
                .andExpect(status().isBadRequest());

        // Dashboard reflects persisted data
        mvc.perform(get("/api/dashboard/summary").with(as(user)))
                .andExpect(jsonPath("$.totalReviews").value(1))
                .andExpect(jsonPath("$.completedReviews").value(1))
                .andExpect(jsonPath("$.openCritical").value(1))
                .andExpect(jsonPath("$.recentReviews[0].id").value(reviewId.toString()));
        mvc.perform(get("/api/dashboard/issue-distribution").with(as(user)))
                .andExpect(jsonPath("$[?(@.category=='SECURITY')].count").value(1));
        mvc.perform(get("/api/dashboard/activity").param("days", "7").with(as(user)))
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[6].reviews").value(1));
    }

    @Test
    void idempotencyKeyPreventsDuplicateReviewJobs() throws Exception {
        UUID first = startReview("retry-key-123456");
        UUID second = startReview("retry-key-123456");
        assertThat(second).isEqualTo(first);
        awaitReview(first, ReviewStatus.COMPLETED);
        assertThat(reviewRepository.countByUserId(user.id())).isEqualTo(1);
    }

    @Test
    void secondReviewCanBeComparedWithTheFirst() throws Exception {
        UUID first = startReview(null);
        awaitReview(first, ReviewStatus.COMPLETED);
        UUID second = startReview(null);
        awaitReview(second, ReviewStatus.COMPLETED);
        mvc.perform(get("/api/reviews/" + second + "/compare").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseReviewId").value(first.toString()))
                .andExpect(jsonPath("$.newFindings.length()").value(0))
                .andExpect(jsonPath("$.fixedFindings.length()").value(0))
                .andExpect(jsonPath("$.recurringFindings.length()").value(findingRepository.findByReviewId(second).size()));
        mvc.perform(get("/api/reviews").param("repositoryId", repositoryId.toString()).param("severity", "CRITICAL")
                        .with(as(user)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void invalidAiOutputStillCompletesWithStaticFindingsAndClearNote() throws Exception {
        LLM.respond(r -> "Sorry, I cannot help with that.");
        UUID reviewId = startReview(null);
        awaitReview(reviewId, ReviewStatus.COMPLETED);
        var review = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(review.getAiStatus()).isEqualTo(AiStatus.FAILED);
        assertThat(review.getNotes()).contains("AI analysis failed");
        assertThat(findingRepository.findByReviewId(reviewId)).isNotEmpty()
                .allMatch(f -> f.getSource() == com.codesage.findings.FindingSource.STATIC_ANALYSIS);
    }

    @Test
    void validationErrorsAreReportedPerField() throws Exception {
        mvc.perform(post("/api/reviews").with(as(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"" + repositoryId + "\",\"type\":\"REPOSITORY\",\"branch\":\"../evil\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.branch").exists());
        mvc.perform(post("/api/reviews").with(as(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"" + repositoryId + "\",\"type\":\"PULL_REQUEST\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancellingAQueuedOrFinishedReview() throws Exception {
        UUID reviewId = startReview(null);
        awaitReview(reviewId, ReviewStatus.COMPLETED);
        mvc.perform(post("/api/reviews/" + reviewId + "/cancel").with(as(user)).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void gitHubOutageIsReportedAsBadGateway() throws Exception {
        GH.on("GET", "/repos/" + owner + "/demo/branches", r -> new okhttp3.mockwebserver.MockResponse().setResponseCode(503));
        mvc.perform(get("/api/repositories/" + repositoryId + "/branches").with(as(user)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
    }
}
