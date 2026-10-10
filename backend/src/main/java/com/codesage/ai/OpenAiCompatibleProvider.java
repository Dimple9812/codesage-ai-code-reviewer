package com.codesage.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** OpenAI Chat Completions API and compatible endpoints (Google Gemini, Azure-style gateways, local servers). */
public class OpenAiCompatibleProvider extends HttpLlmSupport {

    private final String id;
    private final String defaultBaseUrl;

    public OpenAiCompatibleProvider(String id, String defaultBaseUrl, ObjectMapper mapper) {
        super(mapper);
        this.id = id;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String complete(Request request) {
        boolean custom = request.baseUrl() != null && !request.baseUrl().isBlank();
        String base = custom ? trimSlash(request.baseUrl()) : defaultBaseUrl;
        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.system()),
                Map.of("role", "user", "content", request.user())));
        body.put("response_format", Map.of("type", "json_object"));
        if ("openai".equals(id) && !custom) {
            body.put("max_completion_tokens", request.maxOutputTokens());
        } else {
            body.put("max_tokens", request.maxOutputTokens());
        }
        JsonNode json = post(base + "/chat/completions", Map.of("Authorization", "Bearer " + request.apiKey()),
                body, request.timeout());
        JsonNode content = json.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
            throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "The AI provider returned no message content.");
        }
        return content.asText();
    }
}
