package com.codesage.ai;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Retries transient LLM failures (timeouts, 429, 5xx) with capped exponential backoff and jitter. */
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final int maxRetries;
    private final Duration initialBackoff;
    private final Sleeper sleeper;

    public LlmClient(int maxRetries, Duration initialBackoff, Sleeper sleeper) {
        this.maxRetries = Math.max(0, maxRetries);
        this.initialBackoff = initialBackoff;
        this.sleeper = sleeper;
    }

    public String complete(LlmProvider provider, LlmProvider.Request request) {
        int attempt = 0;
        while (true) {
            try {
                return provider.complete(request);
            } catch (LlmException e) {
                if (!e.isRetryable() || attempt >= maxRetries) {
                    throw e;
                }
                Duration wait = backoff(attempt, e.retryAfter());
                log.info("LLM call failed ({}), retrying in {} ms (attempt {}/{})",
                        e.kind(), wait.toMillis(), attempt + 1, maxRetries);
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                attempt++;
            }
        }
    }

    Duration backoff(int attempt, Duration retryAfter) {
        if (retryAfter != null && !retryAfter.isNegative()) {
            return retryAfter.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : retryAfter;
        }
        long base = initialBackoff.toMillis() * (1L << Math.min(attempt, 10));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 4));
        return Duration.ofMillis(Math.min(MAX_BACKOFF.toMillis(), base + jitter));
    }
}
