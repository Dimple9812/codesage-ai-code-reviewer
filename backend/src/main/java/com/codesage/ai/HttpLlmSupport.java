package com.codesage.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared HTTP plumbing and status-code mapping for LLM providers. API keys are never logged. */
abstract class HttpLlmSupport implements LlmProvider {

    protected final HttpClient http;
    protected final ObjectMapper mapper;

    protected HttpLlmSupport(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.getDefault()) // honour JVM proxy settings
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    protected JsonNode post(String url, Map<String, String> headers, Object body, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            headers.forEach(builder::header);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return mapper.readTree(response.body());
            }
            Duration retryAfter = response.headers().firstValue("retry-after")
                    .flatMap(HttpLlmSupport::parseSeconds).orElse(null);
            throw switch (status) {
                case 401, 403 -> new LlmException(LlmException.Kind.AUTHENTICATION,
                        "The AI provider rejected the API key (HTTP " + status + ").");
                case 429 -> new LlmException(LlmException.Kind.RATE_LIMITED,
                        "The AI provider rate limit was reached.", retryAfter, null);
                case 400, 404, 413, 422 -> new LlmException(LlmException.Kind.BAD_REQUEST,
                        "The AI provider rejected the request (HTTP " + status + "). Check the configured model name.");
                default -> new LlmException(LlmException.Kind.UNAVAILABLE,
                        "The AI provider is unavailable (HTTP " + status + ").", retryAfter, null);
            };
        } catch (HttpTimeoutException e) {
            throw new LlmException(LlmException.Kind.TIMEOUT, "The AI provider did not respond in time.", null, e);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.UNAVAILABLE, "Could not reach the AI provider.", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.UNAVAILABLE, "Interrupted while calling the AI provider.", null, e);
        }
    }

    static java.util.Optional<Duration> parseSeconds(String value) {
        try {
            return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    protected static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
