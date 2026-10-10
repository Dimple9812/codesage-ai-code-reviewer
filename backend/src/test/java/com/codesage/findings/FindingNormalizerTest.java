package com.codesage.findings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.codesage.analysis.FindingCandidate;
import com.codesage.analysis.Language;
import com.codesage.analysis.SourceFile;

class FindingNormalizerTest {

    private static final SourceFile FILE = new SourceFile("a.py", Language.PYTHON,
            "import os\npassword = \"hunter2hunter2\"\nos.system(cmd)\n", Set.of(3), Set.of(2, 3));

    private static FindingCandidate ai(Severity s, int line, FindingCategory c, String title) {
        return new FindingCandidate(FindingSource.AI, "llm", Verification.UNVERIFIED, c, s, 0.7, title, "a.py",
                line, line, "desc", "impact", "fix", null, null, false);
    }

    @Test
    void dropsAiDuplicatesOfStaticFindingsAndExactDuplicates() {
        FindingCandidate st = FindingCandidate.staticFinding("codesage-rules", FindingCategory.SECURITY, Severity.HIGH,
                "Shell command execution", "a.py", 3, 3, "d", "i", "r", "SEC-CMD-002", false);
        List<ReviewFinding> out = FindingNormalizer.normalize(UUID.randomUUID(), List.of(st,
                ai(Severity.HIGH, 3, FindingCategory.SECURITY, "Command injection"),
                ai(Severity.LOW, 1, FindingCategory.CODE_QUALITY, "Unused import"),
                ai(Severity.LOW, 1, FindingCategory.CODE_QUALITY, "Unused import")), Map.of("a.py", FILE), Severity.INFO);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getSource()).isEqualTo(FindingSource.STATIC_ANALYSIS);
        assertThat(out.get(0).isInDiff()).isTrue();
        assertThat(out.get(0).getOriginalCode()).isEqualTo("os.system(cmd)");
    }

    @Test
    void filtersBelowMinimumSeverity() {
        List<ReviewFinding> out = FindingNormalizer.normalize(UUID.randomUUID(), List.of(
                ai(Severity.LOW, 1, FindingCategory.BUG, "x"), ai(Severity.HIGH, 3, FindingCategory.BUG, "y")),
                Map.of("a.py", FILE), Severity.MEDIUM);
        assertThat(out).extracting(ReviewFinding::getSeverity).containsExactly(Severity.HIGH);
    }

    @Test
    void redactsSecretsInSensitiveExcerpts() {
        FindingCandidate secret = FindingCandidate.staticFinding("codesage-rules", FindingCategory.SECURITY,
                Severity.HIGH, "Possible hard-coded credential", "a.py", 2, 2, "d", "i", "r", "SEC-SECRET-005", true);
        ReviewFinding f = FindingNormalizer.normalize(UUID.randomUUID(), List.of(secret), Map.of("a.py", FILE),
                Severity.INFO).get(0);
        assertThat(f.getOriginalCode()).doesNotContain("hunter2").contains("[REDACTED]");
    }

    @Test
    void fingerprintIsStableWhenCodeMovesToAnotherLine() {
        SourceFile moved = new SourceFile("a.py", Language.PYTHON, "\n\nimport os\npassword = 1\nos.system(cmd)\n",
                Set.of(), Set.of());
        ReviewFinding before = FindingNormalizer.normalize(UUID.randomUUID(),
                List.of(ai(Severity.HIGH, 3, FindingCategory.SECURITY, "Command injection")), Map.of("a.py", FILE),
                Severity.INFO).get(0);
        ReviewFinding after = FindingNormalizer.normalize(UUID.randomUUID(),
                List.of(ai(Severity.HIGH, 5, FindingCategory.SECURITY, "Shell injection risk")), Map.of("a.py", moved),
                Severity.INFO).get(0);
        assertThat(after.getFingerprint()).isEqualTo(before.getFingerprint());
    }

    @Test
    void scoreFormulaIsDeterministicAndBounded() {
        assertThat(ScoreCalculator.scoreFromPenalty(0)).isEqualTo(100);
        assertThat(ScoreCalculator.scoreFromPenalty(25)).isEqualTo(67);
        assertThat(ScoreCalculator.scoreFromPenalty(10_000)).isGreaterThanOrEqualTo(0).isLessThan(5);
        ReviewFinding f = new ReviewFinding();
        f.setSeverity(Severity.HIGH);
        f.setConfidence(1.0);
        assertThat(ScoreCalculator.score(List.of(f))).isEqualTo(83);
        f.setSeverity(Severity.INFO);
        assertThat(ScoreCalculator.score(List.of(f))).isEqualTo(100);
    }
}
