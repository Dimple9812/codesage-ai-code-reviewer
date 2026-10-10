package com.codesage.findings;

import java.util.Collection;

/**
 * Deterministic review score in the range (0, 100].
 *
 * <pre>
 *   penalty = sum over findings of weight(severity) * confidence
 *             weights: CRITICAL 25, HIGH 10, MEDIUM 4, LOW 1.5, INFO 0
 *   score   = round(100 * 50 / (50 + penalty))
 * </pre>
 *
 * A review with no findings scores 100; one high-confidence CRITICAL issue scores ~67; the score
 * approaches but never reaches zero. It is a heuristic summary of the findings, not a validated
 * measure of software quality.
 */
public final class ScoreCalculator {

    public static final double DAMPING = 50.0;

    private ScoreCalculator() {
    }

    public static double penalty(Severity severity, double confidence) {
        double c = Math.max(0.0, Math.min(1.0, confidence));
        return severity.weight() * c;
    }

    public static int score(Collection<ReviewFinding> findings) {
        double penalty = findings.stream().mapToDouble(f -> penalty(f.getSeverity(), f.getConfidence())).sum();
        return scoreFromPenalty(penalty);
    }

    public static int scoreFromPenalty(double penalty) {
        return (int) Math.round(100.0 * DAMPING / (DAMPING + Math.max(0.0, penalty)));
    }
}
