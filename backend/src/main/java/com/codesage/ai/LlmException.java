package com.codesage.ai;

import java.time.Duration;

/** Failure talking to an LLM provider. {@link #isRetryable()} drives the backoff policy. */
public class LlmException extends RuntimeException {

    public enum Kind { TIMEOUT, RATE_LIMITED, UNAVAILABLE, AUTHENTICATION, BAD_REQUEST, INVALID_RESPONSE }

    private final Kind kind;
    private final Duration retryAfter;

    public LlmException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public LlmException(Kind kind, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean isRetryable() {
        return kind == Kind.TIMEOUT || kind == Kind.RATE_LIMITED || kind == Kind.UNAVAILABLE;
    }
}
