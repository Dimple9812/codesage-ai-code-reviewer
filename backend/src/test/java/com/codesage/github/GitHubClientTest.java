package com.codesage.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.codesage.config.AppProperties;
import com.codesage.support.TestProps;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class GitHubClientTest {

    private MockWebServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        var d = TestProps.defaults();
        AppProperties props = new AppProperties(d.publicUrl(), d.frontendUrl(), d.corsAllowedOrigins(),
                d.tokenEncryptionKey(), new AppProperties.Github("id", "s", "repo", server.url("/").toString(), "",
                Duration.ofSeconds(2)), d.ai(), d.review(), d.analysis(), d.rateLimit());
        client = new GitHubClient(RestClient.builder(), props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void assertCode(MockResponse response, String code) {
        server.enqueue(response);
        assertThatThrownBy(() -> client.getRepository("t", "o", "r"))
                .isInstanceOf(GitHubApiException.class)
                .satisfies(e -> assertThat(((GitHubApiException) e).getCode()).isEqualTo(code));
    }

    @Test
    void mapsGitHubErrorsToStableCodes() {
        assertCode(new MockResponse().setResponseCode(401), "GITHUB_REAUTH_REQUIRED");
        assertCode(new MockResponse().setResponseCode(403).setHeader("x-ratelimit-remaining", "0"), "GITHUB_RATE_LIMITED");
        assertCode(new MockResponse().setResponseCode(429), "GITHUB_RATE_LIMITED");
        assertCode(new MockResponse().setResponseCode(403), "GITHUB_FORBIDDEN");
        assertCode(new MockResponse().setResponseCode(404), "GITHUB_NOT_FOUND");
        assertCode(new MockResponse().setResponseCode(502), "GITHUB_UNAVAILABLE");
    }

    @Test
    void sendsBearerTokenAndEncodesPaths() throws Exception {
        server.enqueue(new MockResponse().setBody("hello"));
        assertThat(client.getFileContent("tok", "o", "r", "src/my file#1.java", "feature/x")).isEqualTo("hello");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer tok");
        assertThat(req.getHeader("Accept")).isEqualTo("application/vnd.github.raw+json");
        assertThat(req.getPath()).isEqualTo("/repos/o/r/contents/src/my%20file%231.java?ref=feature/x");
    }

    @Test
    void paginatesRepositoryListing() throws Exception {
        StringBuilder page1 = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            page1.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"r").append(i)
                    .append("\",\"full_name\":\"o/r").append(i).append("\",\"owner\":{\"login\":\"o\"},\"private\":false}");
        }
        page1.append("]");
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(page1.toString()));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":500,\"name\":\"last\",\"full_name\":\"o/last\",\"owner\":{\"login\":\"o\"},\"private\":true}]"));
        var repos = client.listRepositories("t");
        assertThat(repos).hasSize(101);
        assertThat(repos.get(100).isPrivate()).isTrue();
        assertThat(server.takeRequest().getPath()).contains("page=1");
        assertThat(server.takeRequest().getPath()).contains("page=2");
    }

    @Test
    void refusesNonCommentReviewEvents() {
        var approve = new GitHubModels.CreateReviewRequest("sha", "body", "APPROVE", java.util.List.of());
        assertThatThrownBy(() -> client.createCommentReview("t", "o", "r", 1, approve))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
    }
}
