package com.codesage.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.codesage.analysis.Language;
import com.codesage.analysis.SourceFile;
import com.codesage.config.AppProperties;
import com.codesage.findings.FindingSource;
import com.codesage.findings.Verification;
import com.codesage.review.AiStatus;
import com.codesage.support.TestProps;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;

class AiReviewServiceTest {

    private final AiResponseParser parser = new AiResponseParser(new ObjectMapper(),
            Validation.buildDefaultValidatorFactory().getValidator());

    private static final SourceFile FILE = new SourceFile("src/A.java", Language.JAVA,
            "class A {\n  String f(String s) {\n    return s.trim();\n  }\n}\n", Set.of(), Set.of());

    private static String finding(String path, int line) {
        return "{\"title\":\"NPE\",\"category\":\"BUG\",\"severity\":\"HIGH\",\"confidence\":0.8,\"filePath\":\"" + path
                + "\",\"startLine\":" + line + ",\"endLine\":" + line + ",\"description\":\"s may be null\","
                + "\"impact\":\"crash\",\"recommendation\":\"guard\",\"suggestedCode\":null,\"ruleId\":\"JAVA-NULL-001\"}";
    }

    private static LlmProvider scripted(List<String> responses, AtomicInteger calls, List<String> prompts) {
        return new LlmProvider() {
            @Override
            public String id() {
                return "stub";
            }

            @Override
            public String complete(Request request) {
                prompts.add(request.user());
                String r = responses.get(Math.min(calls.getAndIncrement(), responses.size() - 1));
                if (r.startsWith("THROW:")) {
                    throw new LlmException(LlmException.Kind.valueOf(r.substring(6)), "stub failure");
                }
                return r;
            }
        };
    }

    private AiReviewService service(AppProperties props) {
        return new AiReviewService(props, parser, new LlmClient(0, Duration.ofMillis(1), d -> { }));
    }

    private LlmProviderFactory.ResolvedLlm llm(LlmProvider p) {
        return new LlmProviderFactory.ResolvedLlm(p, "m", "k", null, "server");
    }

    @Test
    void producesUnverifiedAiFindingsAnchoredToRealFiles() {
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new ArrayList<>();
        String response = "{\"summary\":\"one bug\",\"overallScore\":80,\"findings\":[" + finding("src/A.java", 3) + ","
                + finding("src/Hallucinated.java", 1) + "," + finding("src/A.java", 999) + "]}";
        AiReviewService.Outcome outcome = service(TestProps.defaults())
                .review(llm(scripted(List.of(response), calls, prompts)), "o/r", "REPOSITORY", List.of(FILE), () -> false);

        assertThat(outcome.status()).isEqualTo(AiStatus.COMPLETED);
        assertThat(outcome.findings()).hasSize(2);
        assertThat(outcome.findings()).allMatch(f -> f.source() == FindingSource.AI
                && f.verification() == Verification.UNVERIFIED && f.filePath().equals("src/A.java"));
        assertThat(outcome.findings().get(1).startLine()).isNull(); // line beyond EOF is dropped, finding kept
        assertThat(outcome.notes()).anyMatch(n -> n.contains("discarded"));
        assertThat(prompts.get(0)).contains("<untrusted_file path=\"src/A.java\"").contains("    3|     return s.trim();");
    }

    @Test
    void retriesOnceOnMalformedJsonThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new ArrayList<>();
        String good = "{\"summary\":\"ok\",\"overallScore\":90,\"findings\":[]}";
        AiReviewService.Outcome outcome = service(TestProps.defaults())
                .review(llm(scripted(List.of("not json at all", good), calls, prompts)), "o/r", "REPOSITORY",
                        List.of(FILE), () -> false);
        assertThat(outcome.status()).isEqualTo(AiStatus.COMPLETED);
        assertThat(calls).hasValue(2);
        assertThat(prompts.get(1)).contains("previous response could not be used");
    }

    @Test
    void reportsFailureWhenModelKeepsReturningInvalidOutput() {
        AtomicInteger calls = new AtomicInteger();
        AiReviewService.Outcome outcome = service(TestProps.defaults())
                .review(llm(scripted(List.of("{\"bad\":true}"), calls, new ArrayList<>())), "o/r", "REPOSITORY",
                        List.of(FILE), () -> false);
        assertThat(outcome.status()).isEqualTo(AiStatus.FAILED);
        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.notes()).anyMatch(n -> n.startsWith("AI analysis failed"));
    }

    @Test
    void reportsFailureOnTimeoutWithoutThrowing() {
        AtomicInteger calls = new AtomicInteger();
        AiReviewService.Outcome outcome = service(TestProps.defaults())
                .review(llm(scripted(List.of("THROW:TIMEOUT"), calls, new ArrayList<>())), "o/r", "REPOSITORY",
                        List.of(FILE), () -> false);
        assertThat(outcome.status()).isEqualTo(AiStatus.FAILED);
    }

    @Test
    void splitsLargeInputIntoBoundedBatchesAndRespectsRequestCap() {
        AppProperties.Ai small = new AppProperties.Ai("anthropic", "m", "k", "", Duration.ofSeconds(1), 0, 0,
                Duration.ofMillis(1), 1000, 2000, 2);
        String body = "x".repeat(60) + "\n";
        List<SourceFile> files = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            files.add(new SourceFile("f" + i + ".py", Language.PYTHON, body.repeat(20), Set.of(), Set.of()));
        }
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new ArrayList<>();
        AiReviewService.Outcome outcome = service(TestProps.withAi(small)).review(
                llm(scripted(List.of("{\"summary\":\"ok\",\"findings\":[]}"), calls, prompts)), "o/r", "REPOSITORY",
                files, () -> false);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(prompts).allMatch(p -> p.length() < 2000 + ReviewPrompt.user("o/r", "REPOSITORY", List.of(), null).length() + 1000);
        assertThat(outcome.notes()).anyMatch(n -> n.contains("request limit"));
    }

    @Test
    void marksChangedLinesForPullRequests() {
        SourceFile pr = new SourceFile("src/A.java", Language.JAVA, FILE.content(), Set.of(3), Set.of(2, 3, 4));
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new ArrayList<>();
        service(TestProps.defaults()).review(llm(scripted(List.of("{\"summary\":\"ok\",\"findings\":[]}"), calls, prompts)),
                "o/r", "PULL_REQUEST", List.of(pr), () -> false);
        assertThat(prompts.get(0)).contains(">    3|").contains("Focus on the lines marked");
    }
}
