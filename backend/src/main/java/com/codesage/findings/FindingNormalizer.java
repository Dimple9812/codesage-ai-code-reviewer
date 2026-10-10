package com.codesage.findings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.codesage.analysis.FindingCandidate;
import com.codesage.analysis.SourceFile;
import com.codesage.common.Hashing;
import com.codesage.common.Texts;

/**
 * Turns raw candidates into persisted findings: drops duplicates, filters by minimum severity,
 * attaches a short (redacted when sensitive) excerpt, computes a line-shift-tolerant fingerprint
 * used to compare reviews, and flags whether a finding falls inside a PR diff.
 */
public final class FindingNormalizer {

    private static final int MAX_EXCERPT_LINES = 15;
    private static final Pattern QUOTED = Pattern.compile("([\"'`])(?:(?!\\1).){4,}\\1");
    private static final Pattern KEY_LIKE = Pattern.compile("(AKIA|ASIA)[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}");

    private FindingNormalizer() {
    }

    public static List<ReviewFinding> normalize(UUID reviewId, List<FindingCandidate> candidates,
                                                Map<String, SourceFile> files, Severity minSeverity) {
        List<FindingCandidate> statics = new ArrayList<>();
        List<FindingCandidate> ais = new ArrayList<>();
        for (FindingCandidate c : candidates) {
            if (!c.severity().atLeast(minSeverity)) {
                continue;
            }
            (c.source() == FindingSource.STATIC_ANALYSIS ? statics : ais).add(c);
        }
        Map<String, FindingCandidate> unique = new LinkedHashMap<>();
        for (FindingCandidate c : statics) {
            unique.putIfAbsent(exactKey(c), c);
        }
        for (FindingCandidate c : ais) {
            boolean overlapsStatic = statics.stream().anyMatch(s -> overlaps(s, c));
            if (!overlapsStatic) {
                unique.putIfAbsent(exactKey(c), c);
            }
        }
        List<ReviewFinding> out = new ArrayList<>();
        Map<String, Integer> fingerprintCounts = new HashMap<>();
        for (FindingCandidate c : unique.values()) {
            SourceFile file = files.get(c.filePath());
            ReviewFinding f = new ReviewFinding();
            f.setReviewId(reviewId);
            f.setSource(c.source());
            f.setAnalyzer(c.analyzer());
            f.setVerification(c.verification());
            f.setCategory(c.category());
            f.setSeverity(c.severity());
            f.setConfidence(Math.max(0.0, Math.min(1.0, c.confidence())));
            f.setTitle(Texts.truncate(c.title(), 300));
            f.setFilePath(Texts.truncate(c.filePath(), 1000));
            f.setStartLine(c.startLine());
            f.setEndLine(c.endLine());
            f.setDescription(Texts.truncate(c.sensitive() ? redact(c.description()) : c.description(), 7900));
            f.setImpact(Texts.truncate(c.impact(), 3900));
            f.setRecommendation(Texts.truncate(c.recommendation(), 3900));
            f.setSuggestedCode(Texts.truncate(c.suggestedCode(), 7900));
            f.setRuleId(Texts.truncate(c.ruleId(), 120));
            f.setStatus(FindingStatus.OPEN);
            String excerpt = file == null ? null : excerpt(file, c.startLine(), c.endLine());
            f.setOriginalCode(excerpt == null ? null : Texts.truncate(c.sensitive() ? redact(excerpt) : excerpt, 7900));
            f.setInDiff(file != null && c.startLine() != null && file.diffLines().contains(c.startLine()));
            String base = fingerprintBase(c, file);
            int n = fingerprintCounts.merge(base, 1, Integer::sum);
            f.setFingerprint(Hashing.sha256Hex(base + "#" + n));
            out.add(f);
        }
        out.sort(Comparator.comparing(ReviewFinding::getSeverity)
                .thenComparing(ReviewFinding::getFilePath)
                .thenComparing(f -> f.getStartLine() == null ? Integer.MAX_VALUE : f.getStartLine()));
        return out;
    }

    private static String exactKey(FindingCandidate c) {
        String identity = c.ruleId() != null && c.source() == FindingSource.STATIC_ANALYSIS
                ? c.ruleId() : c.title().toLowerCase(Locale.ROOT).replaceAll("\\W+", " ").trim();
        return c.filePath() + "|" + c.startLine() + "|" + c.category() + "|" + identity;
    }

    private static boolean overlaps(FindingCandidate s, FindingCandidate ai) {
        if (!s.filePath().equals(ai.filePath()) || s.category() != ai.category()
                || s.startLine() == null || ai.startLine() == null) {
            return false;
        }
        int sEnd = s.endLine() == null ? s.startLine() : s.endLine();
        int aEnd = ai.endLine() == null ? ai.startLine() : ai.endLine();
        return ai.startLine() <= sEnd + 1 && s.startLine() <= aEnd + 1;
    }

    /**
     * Fingerprint = file + category + normalised content of the first flagged line (+ rule id for static
     * findings). Using line content instead of the line number keeps the fingerprint stable when code moves.
     */
    static String fingerprintBase(FindingCandidate c, SourceFile file) {
        String anchor = "";
        if (file != null && c.startLine() != null) {
            List<String> lines = file.lines();
            if (c.startLine() <= lines.size()) {
                anchor = lines.get(c.startLine() - 1).replaceAll("\\s+", " ").trim();
            }
        }
        String rule = c.source() == FindingSource.STATIC_ANALYSIS && c.ruleId() != null ? c.ruleId() : "";
        return c.filePath() + "|" + c.category() + "|" + rule + "|" + anchor;
    }

    static String excerpt(SourceFile file, Integer start, Integer end) {
        if (start == null) {
            return null;
        }
        List<String> lines = file.lines();
        if (start > lines.size()) {
            return null;
        }
        int last = Math.min(lines.size(), Math.min(end == null ? start : end, start + MAX_EXCERPT_LINES - 1));
        return String.join("\n", lines.subList(start - 1, last));
    }

    static String redact(String text) {
        if (text == null) {
            return null;
        }
        String r = KEY_LIKE.matcher(text).replaceAll("[REDACTED]");
        return QUOTED.matcher(r).replaceAll("$1[REDACTED]$1");
    }
}
