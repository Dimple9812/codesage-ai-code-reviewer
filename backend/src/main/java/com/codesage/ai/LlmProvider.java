package com.codesage.ai;

import java.time.Duration;

/** A chat-style LLM endpoint. Implementations perform exactly one HTTP call; retries live in {@link LlmClient}. */
public interface LlmProvider {

    String id();

    String complete(Request request) throws LlmException;

    record Request(String apiKey, String baseUrl, String model, String system, String user, int maxOutputTokens,
                   Duration timeout) {
    }
}
