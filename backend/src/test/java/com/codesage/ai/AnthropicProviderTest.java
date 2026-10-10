package com.codesage.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class AnthropicProviderTest {

    private MockWebServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private AnthropicProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new AnthropicProvider(mapper);
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private LlmProvider.Request request(Duration timeout) {
        return new LlmProvider.Request("sk-test", server.url("/").toString(), "claude-test", "system prompt",
                "user prompt", 1000, timeout);
    }

    @Test
    void sendsMessagesRequestAndReturnsText() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"summary\\\":\\\"ok\\\"}\"}],\"stop_reason\":\"end_turn\"}"));
        String text = provider.complete(request(Duration.ofSeconds(5)));
        assertThat(text).isEqualTo("{\"summary\":\"ok\"}");

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded.getPath()).isEqualTo("/v1/messages");
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("sk-test");
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("claude-test");
        assertThat(body.path("system").asText()).isEqualTo("system prompt");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("user prompt");
    }

    @Test
    void mapsRateLimitWithRetryAfter() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("retry-after", "12").setBody("{}"));
        assertThatThrownBy(() -> provider.complete(request(Duration.ofSeconds(5))))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> {
                    LlmException le = (LlmException) e;
                    assertThat(le.kind()).isEqualTo(LlmException.Kind.RATE_LIMITED);
                    assertThat(le.retryAfter()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(le.isRetryable()).isTrue();
                });
    }

    @Test
    void mapsAuthOverloadAndBadRequest() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));
        server.enqueue(new MockResponse().setResponseCode(529).setBody("{}"));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{}"));
        assertKind(LlmException.Kind.AUTHENTICATION);
        assertKind(LlmException.Kind.UNAVAILABLE);
        assertKind(LlmException.Kind.BAD_REQUEST);
    }

    @Test
    void timesOutWhenProviderIsSlow() {
        server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(3, TimeUnit.SECONDS));
        assertThatThrownBy(() -> provider.complete(request(Duration.ofMillis(300))))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).kind()).isEqualTo(LlmException.Kind.TIMEOUT));
    }

    @Test
    void apiKeyNeverAppearsInErrorMessages() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid x-api-key sk-test\"}"));
        assertThatThrownBy(() -> provider.complete(request(Duration.ofSeconds(5))))
                .hasMessageNotContaining("sk-test");
    }

    private void assertKind(LlmException.Kind kind) {
        assertThatThrownBy(() -> provider.complete(request(Duration.ofSeconds(5))))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).kind()).isEqualTo(kind));
    }
}
