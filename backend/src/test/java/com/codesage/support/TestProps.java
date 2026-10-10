package com.codesage.support;

import java.time.Duration;

import com.codesage.config.AppProperties;

public final class TestProps {

    private TestProps() {
    }

    public static AppProperties defaults() {
        return withAi(new AppProperties.Ai("anthropic", "test-model", "key", "", Duration.ofSeconds(5), 2, 1,
                Duration.ofMillis(1), 4000, 60000, 8));
    }

    public static AppProperties withAi(AppProperties.Ai ai) {
        return new AppProperties("http://localhost:3000", "http://localhost:3000", "http://localhost:3000",
                "unit-test-key",
                new AppProperties.Github("id", "secret", "read:user,repo", "https://api.github.com", "whsecret",
                        Duration.ofSeconds(5)),
                ai,
                new AppProperties.Review(60, 120000, 600000, 1, 3, Duration.ofMinutes(30)),
                new AppProperties.Analysis(true, true, false, "semgrep", "p/default", Duration.ofSeconds(30)),
                new AppProperties.RateLimit(true, 120, 30, "memory"));
    }
}
