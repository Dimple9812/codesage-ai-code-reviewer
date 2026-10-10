package com.codesage.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class LlmClientTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final LlmProvider.Request request = new LlmProvider.Request("k", null, "m", "s", "u", 100, Duration.ofSeconds(1));

    private LlmProvider failingThenOk(int failures, LlmException error, AtomicInteger calls) {
        return new LlmProvider() {
            @Override
            public String id() {
                return "stub";
            }

            @Override
            public String complete(Request r) {
                if (calls.incrementAndGet() <= failures) {
                    throw error;
                }
                return "ok";
            }
        };
    }

    @Test
    void retriesRateLimitHonouringRetryAfter() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = new LlmClient(3, Duration.ofMillis(10), sleeps::add);
        String result = client.complete(failingThenOk(2, new LlmException(LlmException.Kind.RATE_LIMITED, "429",
                Duration.ofSeconds(7), null), calls), request);
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(7));
    }

    @Test
    void retriesTimeoutsWithExponentialBackoffThenGivesUp() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = new LlmClient(2, Duration.ofMillis(100), sleeps::add);
        assertThatThrownBy(() -> client.complete(failingThenOk(10,
                new LlmException(LlmException.Kind.TIMEOUT, "timeout"), calls), request))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).kind()).isEqualTo(LlmException.Kind.TIMEOUT));
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(1)).isGreaterThan(sleeps.get(0));
    }

    @Test
    void doesNotRetryAuthenticationErrors() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = new LlmClient(5, Duration.ofMillis(1), sleeps::add);
        assertThatThrownBy(() -> client.complete(failingThenOk(10,
                new LlmException(LlmException.Kind.AUTHENTICATION, "bad key"), calls), request))
                .isInstanceOf(LlmException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void capsRetryAfterAtSixtySeconds() {
        LlmClient client = new LlmClient(1, Duration.ofMillis(1), sleeps::add);
        assertThat(client.backoff(0, Duration.ofMinutes(10))).isEqualTo(Duration.ofSeconds(60));
    }
}
