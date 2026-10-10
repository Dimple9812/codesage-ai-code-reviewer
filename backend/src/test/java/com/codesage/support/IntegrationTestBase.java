package com.codesage.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.codesage.ai.LlmProviderFactory;
import com.codesage.auth.UserAccountService;
import com.codesage.review.CodeReviewRepository;
import com.codesage.review.ReviewStatus;
import com.codesage.security.CodeSageOAuth2UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockWebServer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final MockWebServer GITHUB = new MockWebServer();
    protected static final GitHubStub GH = new GitHubStub();
    protected static final StubLlmProvider LLM = new StubLlmProvider();

    static {
        GITHUB.setDispatcher(GH);
        try {
            GITHUB.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void githubUrl(DynamicPropertyRegistry registry) {
        registry.add("codesage.github.api-url", () -> GITHUB.url("/").toString());
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected UserAccountService accounts;
    @Autowired
    protected LlmProviderFactory llmFactory;
    @Autowired
    protected CodeReviewRepository reviewRepository;

    @BeforeEach
    void registerStubLlm() {
        llmFactory.register("anthropic", LLM);
    }

    protected record TestUser(UUID id, String login, String token, long githubId) {
    }

    protected TestUser newUser() {
        long githubId = ThreadLocalRandom.current().nextLong(1_000_000, 9_000_000_000L);
        String login = "user" + githubId;
        String token = "tok-" + login;
        UUID id = accounts.upsertOnLogin(githubId, login, "Test " + login, null, null, token, "read:user,repo");
        return new TestUser(id, login, token, githubId);
    }

    protected static RequestPostProcessor as(TestUser user) {
        return oauth2Login().attributes(a -> {
            a.put(CodeSageOAuth2UserService.USER_ID_ATTRIBUTE, user.id().toString());
            a.put("login", user.login());
        });
    }

    protected void awaitReview(UUID reviewId, ReviewStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> reviewRepository.findById(reviewId).map(r -> r.getStatus() == expected).orElse(false));
    }

    protected static String repoJson(long id, String owner, String name, boolean isPrivate) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"full_name\":\"" + owner + "/" + name + "\","
                + "\"owner\":{\"login\":\"" + owner + "\"},\"description\":\"Demo repository\",\"language\":\"Java\","
                + "\"private\":" + isPrivate + ",\"default_branch\":\"main\",\"html_url\":\"https://github.com/" + owner
                + "/" + name + "\",\"pushed_at\":\"2026-10-01T10:00:00Z\"}";
    }
}
