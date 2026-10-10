package com.codesage.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codesage.findings.FindingCategory;
import com.codesage.findings.FindingStatus;
import com.codesage.findings.ReviewFindingRepository;
import com.codesage.findings.Severity;
import com.codesage.repository.GitRepositoryRepository;
import com.codesage.review.CodeReview;
import com.codesage.review.CodeReviewRepository;
import com.codesage.review.ReviewDtos.ReviewSummaryDto;
import com.codesage.review.ReviewService;
import com.codesage.review.ReviewStatus;
import com.codesage.security.CurrentUser;

import io.swagger.v3.oas.annotations.tags.Tag;

/** All dashboard figures are computed from the user's persisted data - nothing is hard-coded. */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    public record Summary(long repositoriesAccessible, long repositoriesReviewed, long totalReviews,
                          long completedReviews, long activeReviews, Double averageScore, long openCritical,
                          long openHigh, long openFindings, long resolvedFindings, long dismissedFindings,
                          List<ReviewSummaryDto> recentReviews) {
    }

    public record ActivityPoint(String date, long reviews) {
    }

    public record CategoryCount(FindingCategory category, long count) {
    }

    private final CodeReviewRepository reviews;
    private final ReviewFindingRepository findings;
    private final GitRepositoryRepository repositories;
    private final ReviewService reviewService;
    private final CurrentUser currentUser;
    private final Clock clock;

    public DashboardController(CodeReviewRepository reviews, ReviewFindingRepository findings,
                               GitRepositoryRepository repositories, ReviewService reviewService,
                               CurrentUser currentUser, Clock clock) {
        this.reviews = reviews;
        this.findings = findings;
        this.repositories = repositories;
        this.reviewService = reviewService;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public Summary summary() {
        UUID userId = currentUser.requireId();
        Map<Severity, Long> openBySeverity = new EnumMap<>(Severity.class);
        for (Object[] row : findings.countOpenBySeverity(userId)) {
            openBySeverity.put((Severity) row[0], (Long) row[1]);
        }
        Map<FindingStatus, Long> byStatus = new EnumMap<>(FindingStatus.class);
        for (Object[] row : findings.countByStatus(userId)) {
            byStatus.put((FindingStatus) row[0], (Long) row[1]);
        }
        List<CodeReview> recent = reviews.findTop5ByUserIdOrderByCreatedAtDesc(userId);
        var repos = reviewService.loadRepos(recent);
        var prs = reviewService.loadPrs(recent);
        Double avg = reviews.averageScore(userId);
        return new Summary(
                repositories.countByUserId(userId),
                reviews.countReviewedRepositories(userId),
                reviews.countByUserId(userId),
                reviews.countByUserIdAndStatus(userId, ReviewStatus.COMPLETED),
                reviews.countByUserIdAndStatus(userId, ReviewStatus.QUEUED)
                        + reviews.countByUserIdAndStatus(userId, ReviewStatus.RUNNING),
                avg == null ? null : Math.round(avg * 10.0) / 10.0,
                openBySeverity.getOrDefault(Severity.CRITICAL, 0L),
                openBySeverity.getOrDefault(Severity.HIGH, 0L),
                byStatus.getOrDefault(FindingStatus.OPEN, 0L),
                byStatus.getOrDefault(FindingStatus.RESOLVED, 0L),
                byStatus.getOrDefault(FindingStatus.DISMISSED, 0L),
                recent.stream().map(r -> ReviewService.summary(r, repos.get(r.getRepositoryId()),
                        prs.get(r.getPullRequestId()))).toList());
    }

    @GetMapping("/activity")
    @Transactional(readOnly = true)
    public List<ActivityPoint> activity(@RequestParam(defaultValue = "30") int days) {
        int span = Math.max(1, Math.min(days, 180));
        UUID userId = currentUser.requireId();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = today.minusDays(span - 1L);
        Instant since = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<LocalDate, Long> counts = new TreeMap<>();
        for (int i = 0; i < span; i++) {
            counts.put(start.plusDays(i), 0L);
        }
        for (Instant created : reviews.findCreatedAtSince(userId, since)) {
            counts.merge(LocalDate.ofInstant(created.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC), 1L, Long::sum);
        }
        List<ActivityPoint> points = new ArrayList<>();
        counts.forEach((d, c) -> points.add(new ActivityPoint(d.toString(), c)));
        return points;
    }

    @GetMapping("/issue-distribution")
    @Transactional(readOnly = true)
    public List<CategoryCount> distribution() {
        UUID userId = currentUser.requireId();
        List<CategoryCount> out = new ArrayList<>();
        for (Object[] row : findings.countByCategory(userId)) {
            out.add(new CategoryCount((FindingCategory) row[0], (Long) row[1]));
        }
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }
}
