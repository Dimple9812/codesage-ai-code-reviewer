package com.codesage.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.codesage.findings.FindingDto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public static final String BRANCH_PATTERN = "^(?!.*\\.\\.)(?!/)[A-Za-z0-9._/+-]{1,255}$";

    public record CreateReviewRequest(
            @NotNull UUID repositoryId,
            @NotNull ReviewType type,
            @Pattern(regexp = BRANCH_PATTERN, message = "is not a valid branch name") String branch,
            @Min(1) Integer pullRequestNumber) {
    }

    public record SeverityCounts(int critical, int high, int medium, int low, int info) {
    }

    public record ReviewSummaryDto(UUID id, UUID repositoryId, String repositoryFullName, ReviewType type,
                                   String branch, String commitSha, Integer pullRequestNumber,
                                   String pullRequestTitle, ReviewStatus status, TriggerSource triggerSource,
                                   Integer score, AiStatus aiStatus, SeverityCounts counts, int totalFindings,
                                   Instant createdAt, Instant completedAt) {
    }

    public record ReviewDetailDto(ReviewSummaryDto review, String summary, List<String> notes, String errorMessage,
                                  String aiProvider, String aiModel, int filesConsidered, int filesAnalyzed,
                                  int filesSkipped, Instant startedAt, String repositoryHtmlUrl,
                                  String pullRequestHtmlUrl, String scoreFormula) {
    }

    public record ReviewStatusDto(UUID id, ReviewStatus status, AiStatus aiStatus, Instant startedAt,
                                  Instant completedAt, String errorMessage, boolean terminal) {
    }

    public record ReviewFileDto(String path, String language, boolean analyzed, String skipReason,
                                String changedLines, long findingCount) {
    }

    public record FileContentDto(String path, String language, String content, String commitSha) {
    }

    public record ComparisonDto(UUID baseReviewId, UUID currentReviewId, Instant baseCreatedAt,
                                List<FindingDto> newFindings, List<FindingDto> fixedFindings,
                                List<FindingDto> recurringFindings) {
    }

    public record PublishCommentsRequest(
            @AssertTrue(message = "must be true to publish comments to GitHub") boolean confirm,
            @NotEmpty @Size(max = 50) List<@NotNull UUID> findingIds) {
    }

    public record PublishCommentsResponse(int published, int skipped, String githubReviewUrl) {
    }

    public record UpdateFindingRequest(@NotNull com.codesage.findings.FindingStatus status) {
    }
}
