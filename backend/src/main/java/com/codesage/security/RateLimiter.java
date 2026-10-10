package com.codesage.security;

import java.time.Duration;

public interface RateLimiter {

    /** Returns true if the call identified by key is within {@code limit} per {@code window}. */
    boolean tryAcquire(String key, int limit, Duration window);
}
