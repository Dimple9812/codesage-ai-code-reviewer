package com.codesage.findings;

public enum Severity {
    CRITICAL(25.0),
    HIGH(10.0),
    MEDIUM(4.0),
    LOW(1.5),
    INFO(0.0);

    /** Penalty weight used by the documented scoring formula (see ScoreCalculator). */
    private final double weight;

    Severity(double weight) {
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }

    /** True when this severity is at least as severe as {@code other}. */
    public boolean atLeast(Severity other) {
        return this.ordinal() <= other.ordinal();
    }
}
