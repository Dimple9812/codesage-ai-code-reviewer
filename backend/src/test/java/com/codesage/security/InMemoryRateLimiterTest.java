package com.codesage.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

    @Test
    void enforcesLimitPerWindowAndKey() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
        assertThat(limiter.tryAcquire("a", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("a", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("a", 2, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire("b", 2, Duration.ofMinutes(1))).isTrue();
        now.set(now.get().plusSeconds(61));
        assertThat(limiter.tryAcquire("a", 2, Duration.ofMinutes(1))).isTrue();
    }
}
