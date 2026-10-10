package com.codesage.ai;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/** Extracts and validates the model's JSON. Malformed or schema-violating output raises INVALID_RESPONSE. */
@Component
public class AiResponseParser {

    private final ObjectMapper mapper;
    private final Validator validator;

    public AiResponseParser(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.validator = validator;
    }

    public AiReviewPayload parse(String raw) {
        String json = extractJsonObject(raw);
        AiReviewPayload payload;
        try {
            payload = mapper.readValue(json, AiReviewPayload.class);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmException.Kind.INVALID_RESPONSE,
                    "The AI response was not valid JSON: " + e.getOriginalMessage());
        }
        Set<ConstraintViolation<AiReviewPayload>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            String detail = violations.stream().limit(5)
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "The AI response violated the schema: " + detail);
        }
        return payload;
    }

    static String extractJsonObject(String raw) {
        if (raw == null) {
            throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "Empty AI response");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int fenceEnd = text.lastIndexOf("```");
            if (firstNewline > 0 && fenceEnd > firstNewline) {
                text = text.substring(firstNewline + 1, fenceEnd).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmException(LlmException.Kind.INVALID_RESPONSE, "The AI response did not contain a JSON object.");
        }
        return text.substring(start, end + 1);
    }
}
