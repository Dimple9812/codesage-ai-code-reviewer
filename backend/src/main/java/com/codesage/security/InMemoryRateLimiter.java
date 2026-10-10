package com.codesage.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-window limiter for single-instance deployments. Use the Redis limiter when scaling out. */
public class InMemoryRateLimiter implements RateLimiter {

    private record Window(long startMillis, AtomicInteger count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long windowMillis = window.toMillis();
        Window w = windows.compute(key, (k, existing) ->
                existing == null || now - existing.startMillis() >= windowMillis
                        ? new Window(now, new AtomicInteger())
                        : existing);
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= windowMillis);
        }
        return w.count().incrementAndGet() <= limit;
    }
}
