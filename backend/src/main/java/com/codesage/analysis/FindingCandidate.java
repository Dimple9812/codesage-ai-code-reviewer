package com.codesage.analysis;

import com.codesage.findings.FindingCategory;
import com.codesage.findings.FindingSource;
import com.codesage.findings.Severity;
import com.codesage.findings.Verification;

/** A finding produced by any analyzer (static or AI), before normalisation and persistence. */
public record FindingCandidate(
        FindingSource source,
        String analyzer,
        Verification verification,
        FindingCategory category,
        Severity severity,
        double confidence,
        String title,
        String filePath,
        Integer startLine,
        Integer endLine,
        String description,
        String impact,
        String recommendation,
        String suggestedCode,
        String ruleId,
        boolean sensitive) {

    /** Convenience factory for static analyzer findings. */
    public static FindingCandidate staticFinding(String analyzer, FindingCategory category, Severity severity,
                                                 String title, String filePath, Integer startLine, Integer endLine,
                                                 String description, String impact, String recommendation,
                                                 String ruleId, boolean sensitive) {
        return new FindingCandidate(FindingSource.STATIC_ANALYSIS, analyzer, Verification.STATIC_ANALYZER_REPORTED,
                category, severity, 0.9, title, filePath, startLine, endLine, description, impact, recommendation,
                null, ruleId, sensitive);
    }
}
