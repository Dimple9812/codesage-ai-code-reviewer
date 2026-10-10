package com.codesage.pullrequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codesage.common.Texts;
import com.codesage.exception.ApiException;
import com.codesage.findings.FindingSource;
import com.codesage.findings.ReviewFinding;
import com.codesage.findings.ReviewFindingRepository;
import com.codesage.github.GitHubClient;
import com.codesage.github.GitHubModels;
import com.codesage.github.GitHubTokenService;
import com.codesage.repository.GitRepository;
import com.codesage.repository.GitRepositoryRepository;
import com.codesage.review.CodeReview;
import com.codesage.review.ReviewDtos.PublishCommentsResponse;
import com.codesage.review.ReviewService;
import com.codesage.review.ReviewStatus;
import com.codesage.review.ReviewType;

/**
 * Publishes selected findings as a single COMMENT review on the pull request, only after explicit
 * user confirmation. CodeSage never approves, requests changes, merges, closes or pushes code.
 */
@Service
public class PublishCommentsService {

    private final ReviewService reviews;
    private final ReviewFindingRepository findings;
    private final GitRepositoryRepository repositories;
    private final PullRequestRecordRepository pullRequests;
    private final GitHubClient github;
    private final GitHubTokenService tokens;

    public PublishCommentsService(ReviewService reviews, ReviewFindingRepository findings,
                                  GitRepositoryRepository repositories, PullRequestRecordRepository pullRequests,
                                  GitHubClient github, GitHubTokenService tokens) {
        this.reviews = reviews;
        this.findings = findings;
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.github = github;
        this.tokens = tokens;
    }

    @Transactional
    public PublishCommentsResponse publish(UUID userId, UUID reviewId, List<UUID> findingIds) {
        CodeReview review = reviews.requireOwned(userId, reviewId);
        if (review.getType() != ReviewType.PULL_REQUEST || review.getPullRequestId() == null) {
            throw ApiException.badRequest("Only pull request reviews can be published to GitHub");
        }
        if (review.getStatus() != ReviewStatus.COMPLETED || review.getCommitSha() == null) {
            throw ApiException.conflict("The review must be completed before publishing comments");
        }
        GitRepository repo = repositories.findById(review.getRepositoryId())
                .orElseThrow(() -> ApiException.notFound("Repository"));
        PullRequestRecord pr = pullRequests.findById(review.getPullRequestId())
                .orElseThrow(() -> ApiException.notFound("Pull request"));

        List<ReviewFinding> selected = findings.findByReviewIdAndIdIn(reviewId, findingIds);
        List<GitHubModels.ReviewComment> comments = new ArrayList<>();
        List<ReviewFinding> publishable = new ArrayList<>();
        for (ReviewFinding f : selected) {
            if (f.isInDiff() && f.getStartLine() != null && f.getPublishedAt() == null) {
                comments.add(new GitHubModels.ReviewComment(f.getFilePath(), f.getStartLine(), "RIGHT", body(f)));
                publishable.add(f);
            }
        }
        int skipped = findingIds.size() - publishable.size();
        if (comments.isEmpty()) {
            throw ApiException.badRequest("None of the selected findings can be published: they must be unpublished and "
                    + "located on a line that is part of the pull request diff");
        }
        GitHubModels.CreateReviewRequest request = new GitHubModels.CreateReviewRequest(review.getCommitSha(),
                "Automated review by CodeSage AI: " + comments.size() + " comment(s). AI-generated findings are "
                        + "unverified suggestions; static analyzer findings come from deterministic rules.",
                "COMMENT", comments);
        GitHubModels.CreatedReview created = github.createCommentReview(tokens.requireToken(userId), repo.getOwner(),
                repo.getName(), pr.getNumber(), request);
        Instant now = Instant.now();
        publishable.forEach(f -> f.setPublishedAt(now));
        return new PublishCommentsResponse(publishable.size(), skipped, created == null ? pr.getHtmlUrl() : created.htmlUrl());
    }

    static String body(ReviewFinding f) {
        String origin = f.getSource() == FindingSource.AI
                ? "AI-generated, unverified (confidence " + String.format("%.2f", f.getConfidence()) + ")"
                : "Static analysis: " + f.getAnalyzer() + (f.getRuleId() == null ? "" : " `" + f.getRuleId() + "`");
        StringBuilder sb = new StringBuilder();
        sb.append("**[").append(f.getSeverity()).append("] ").append(escape(f.getTitle())).append("**  \n")
          .append("_").append(f.getCategory()).append(" · ").append(origin).append("_\n\n")
          .append(escape(Texts.truncate(f.getDescription(), 2000))).append('\n');
        if (f.getImpact() != null && !f.getImpact().isBlank()) {
            sb.append("\n**Impact:** ").append(escape(Texts.truncate(f.getImpact(), 1000))).append('\n');
        }
        if (f.getRecommendation() != null && !f.getRecommendation().isBlank()) {
            sb.append("\n**Recommendation:** ").append(escape(Texts.truncate(f.getRecommendation(), 1000))).append('\n');
        }
        if (f.getSuggestedCode() != null && !f.getSuggestedCode().isBlank()) {
            sb.append("\n<details><summary>Suggested code (review before applying)</summary>\n\n```\n")
              .append(Texts.truncate(f.getSuggestedCode(), 3000).replace("```", "``​`"))
              .append("\n```\n</details>\n");
        }
        sb.append("\n<sub>Posted by CodeSage AI after explicit user confirmation.</sub>");
        return sb.toString();
    }

    /** Neutralise mentions and HTML so AI text cannot ping users or inject markup into the PR. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("<", "&lt;").replace(">", "&gt;").replace("@", "@​");
    }
}
