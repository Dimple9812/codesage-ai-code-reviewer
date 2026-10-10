package com.codesage.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;

class AiResponseParserTest {

    private final AiResponseParser parser = new AiResponseParser(new ObjectMapper(),
            Validation.buildDefaultValidatorFactory().getValidator());

    static final String VALID = """
            {"summary":"One issue","overallScore":82,"findings":[{"title":"Possible null pointer exception",
            "category":"BUG","severity":"HIGH","confidence":0.94,"filePath":"src/A.java","startLine":42,"endLine":42,
            "description":"value may be null","impact":"500 error","recommendation":"check null",
            "suggestedCode":"if (v != null) {}","ruleId":"JAVA-NULL-001"}]}
            """;

    @Test
    void parsesValidResponse() {
        AiReviewPayload payload = parser.parse(VALID);
        assertThat(payload.findings()).hasSize(1);
        assertThat(payload.findings().get(0).severity()).isEqualTo("HIGH");
    }

    @Test
    void stripsMarkdownFencesAndSurroundingProse() {
        assertThat(parser.parse("```json\n" + VALID + "\n```").findings()).hasSize(1);
        assertThat(parser.parse("Here is the review:\n" + VALID + "\nThanks").summary()).isEqualTo("One issue");
    }

    @Test
    void rejectsNonJson() {
        assertThatThrownBy(() -> parser.parse("I could not review this code."))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).kind()).isEqualTo(LlmException.Kind.INVALID_RESPONSE));
        assertThatThrownBy(() -> parser.parse("{\"summary\": \"x\", \"findings\": [ }"))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void rejectsSchemaViolations() {
        String badSeverity = VALID.replace("\"HIGH\"", "\"SEVERE\"");
        String badConfidence = VALID.replace("0.94", "1.7");
        String missingFindings = "{\"summary\":\"ok\"}";
        String missingPath = VALID.replace("\"filePath\":\"src/A.java\",", "");
        for (String bad : new String[] {badSeverity, badConfidence, missingFindings, missingPath}) {
            assertThatThrownBy(() -> parser.parse(bad))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("schema");
        }
    }
}
