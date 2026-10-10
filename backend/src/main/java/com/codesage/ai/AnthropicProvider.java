package com.codesage.ai;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Anthropic Messages API (https://docs.claude.com). */
@Component
public class AnthropicProvider extends HttpLlmSupport {

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    public AnthropicProvider(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public String complete(Request request) {
        String base = request.baseUrl() == null || request.baseUrl().isBlank() ? DEFAULT_BASE_URL : trimSlash(request.baseUrl());
        Map<String, Object> body = Map.of(
                "model", request.model(),
                "max_tokens", request.maxOutputTokens(),
                "temperature", 0,
                "system", request.system(),
                "messages", List.of(Map.of("role", "user", "content", request.user())));
        JsonNode json = post(base + "/v1/messages",
                Map.of("x-api-key", request.apiKey(), "anthropic-version", "2023-06-01"),
                body, request.timeout());
        StringBuilder text = new StringBuilder();
        for (JsonNode block : json.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        if (text.isEmpty()) {
            throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "The AI provider returned no text content.");
        }
        return text.toString();
    }
}
