package com.codesage.ai;

import java.time.Duration;

/** Abstraction over Thread.sleep so retry/backoff behaviour is testable without real delays. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    Sleeper SYSTEM = d -> Thread.sleep(d.toMillis());
}
