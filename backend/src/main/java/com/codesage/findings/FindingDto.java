package com.codesage.findings;

import java.time.Instant;
import java.util.UUID;

public record FindingDto(UUID id, UUID reviewId, FindingSource source, String analyzer, Verification verification,
                         FindingCategory category, Severity severity, double confidence, String title, String filePath,
                         Integer startLine, Integer endLine, String description, String impact,
                         String recommendation, String suggestedCode, String originalCode, String ruleId,
                         FindingStatus status, boolean inDiff, Instant publishedAt, Instant createdAt) {

    public static FindingDto from(ReviewFinding f) {
        return new FindingDto(f.getId(), f.getReviewId(), f.getSource(), f.getAnalyzer(), f.getVerification(),
                f.getCategory(), f.getSeverity(), f.getConfidence(), f.getTitle(), f.getFilePath(), f.getStartLine(),
                f.getEndLine(), f.getDescription(), f.getImpact(), f.getRecommendation(), f.getSuggestedCode(),
                f.getOriginalCode(), f.getRuleId(), f.getStatus(), f.isInDiff(), f.getPublishedAt(), f.getCreatedAt());
    }
}
