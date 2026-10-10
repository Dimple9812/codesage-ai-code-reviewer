package com.codesage.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly typed application configuration. Every secret is supplied through environment
 * variables (see .env.example) and never logged.
 */
@ConfigurationProperties(prefix = "codesage")
public record AppProperties(
        @DefaultValue("http://localhost:3000") String publicUrl,
        @DefaultValue("http://localhost:3000") String frontendUrl,
        @DefaultValue("http://localhost:3000") String corsAllowedOrigins,
        @DefaultValue("") String tokenEncryptionKey,
        @DefaultValue Github github,
        @DefaultValue Ai ai,
        @DefaultValue Review review,
        @DefaultValue Analysis analysis,
        @DefaultValue RateLimit rateLimit) {

    public List<String> corsOrigins() {
        return Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public record Github(
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("read:user,user:email,repo") String scopes,
            @DefaultValue("https://api.github.com") String apiUrl,
            @DefaultValue("") String webhookSecret,
            @DefaultValue("20s") Duration timeout) {

        public boolean oauthConfigured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }

        public boolean webhookConfigured() {
            return webhookSecret != null && !webhookSecret.isBlank();
        }

        public List<String> scopeList() {
            return Arrays.stream(scopes.split("[,\\s]+")).filter(s -> !s.isBlank()).toList();
        }
    }

    public record Ai(
            @DefaultValue("anthropic") String provider,
            @DefaultValue("claude-sonnet-5-5") String model,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String baseUrl,
            @DefaultValue("120s") Duration timeout,
            @DefaultValue("3") int maxRetries,
            @DefaultValue("1") int maxSchemaRetries,
            @DefaultValue("2s") Duration initialBackoff,
            @DefaultValue("8000") int maxOutputTokens,
            @DefaultValue("60000") int maxCharsPerRequest,
            @DefaultValue("8") int maxRequestsPerReview) {
    }

    public record Review(
            @DefaultValue("60") int maxFiles,
            @DefaultValue("120000") int maxFileBytes,
            @DefaultValue("600000") int maxTotalBytes,
            @DefaultValue("2") int workerThreads,
            @DefaultValue("3") int jobMaxAttempts,
            @DefaultValue("30m") Duration staleJobTimeout) {
    }

    public record Analysis(
            @DefaultValue("true") boolean pmdEnabled,
            @DefaultValue("true") boolean rulesEnabled,
            @DefaultValue("false") boolean semgrepEnabled,
            @DefaultValue("semgrep") String semgrepPath,
            @DefaultValue("p/default") String semgrepConfig,
            @DefaultValue("120s") Duration semgrepTimeout) {
    }

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("120") int requestsPerMinute,
            @DefaultValue("30") int reviewCreationsPerHour,
            @DefaultValue("memory") String backend) {
    }
}
