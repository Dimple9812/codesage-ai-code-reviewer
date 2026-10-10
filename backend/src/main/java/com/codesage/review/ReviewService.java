package com.codesage.review;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codesage.analysis.Language;
import com.codesage.common.PageResponse;
import com.codesage.config.AppProperties;
import com.codesage.exception.ApiException;
import com.codesage.findings.FindingDto;
import com.codesage.findings.ReviewFinding;
import com.codesage.findings.ReviewFindingRepository;
import com.codesage.findings.Severity;
import com.codesage.github.GitHubClient;
import com.codesage.github.GitHubModels;
import com.codesage.github.GitHubTokenService;
import com.codesage.pullrequest.PullRequestRecord;
import com.codesage.pullrequest.PullRequestRecordRepository;
import com.codesage.repository.GitRepository;
import com.codesage.repository.GitRepositoryRepository;
import com.codesage.repository.RepositoryService;
import com.codesage.review.ReviewDtos.ComparisonDto;
import com.codesage.review.ReviewDtos.CreateReviewRequest;
import com.codesage.review.ReviewDtos.FileContentDto;
import com.codesage.review.ReviewDtos.ReviewDetailDto;
import com.codesage.review.ReviewDtos.ReviewFileDto;
import com.codesage.review.ReviewDtos.ReviewStatusDto;
import com.codesage.review.ReviewDtos.ReviewSummaryDto;
import com.codesage.review.ReviewDtos.SeverityCounts;
import com.codesage.security.RateLimiter;

@Service
public class ReviewService {

    public static final String SCORE_FORMULA = "score = round(100 x 50 / (50 + sum(weight(severity) x confidence))); "
            + "weights CRITICAL 25, HIGH 10, MEDIUM 4, LOW 1.5, INFO 0. A heuristic summary, not a validated quality metric.";

    private final CodeReviewRepository reviews;
    private final ReviewJobRepository jobs;
    private final ReviewFindingRepository findings;
    private final ReviewFileRepository files;
    private final GitRepositoryRepository repositories;
    private final PullRequestRecordRepository pullRequests;
    private final RepositoryService repositoryService;
    private final GitHubClient github;
    private final GitHubTokenService tokens;
    private final ReviewJobService jobService;
    private final RateLimiter rateLimiter;
    private final AppProperties props;

    public ReviewService(CodeReviewRepository reviews, ReviewJobRepository jobs, ReviewFindingRepository findings,
                         ReviewFileRepository files, GitRepositoryRepository repositories,
                         PullRequestRecordRepository pullRequests, RepositoryService repositoryService,
                         GitHubClient github, GitHubTokenService tokens, ReviewJobService jobService,
                         RateLimiter rateLimiter, AppProperties props) {
        this.reviews = reviews;
        this.jobs = jobs;
        this.findings = findings;
        this.files = files;
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.repositoryService = repositoryService;
        this.github = github;
        this.tokens = tokens;
        this.jobService = jobService;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    // ---------------------------------------------------------------- creation

    @Transactional
    public ReviewSummaryDto create(UUID userId, CreateReviewRequest request, String idempotencyKeyHeader) {
        GitRepository repo = repositoryService.requireOwned(userId, request.repositoryId());

        String idempotencyKey = null;
        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
            if (!idempotencyKeyHeader.matches("^[A-Za-z0-9_-]{8,100}$")) {
                throw ApiException.badRequest("Idempotency-Key must be 8-100 characters of [A-Za-z0-9_-]");
            }
            idempotencyKey = "manual:" + userId + ":" + idempotencyKeyHeader;
            Optional<ReviewJob> existing = jobs.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return summary(requireOwned(userId, existing.get().getReviewId()));
            }
        }
        if (props.rateLimit().enabled() && !rateLimiter.tryAcquire("review-create:" + userId,
                props.rateLimit().reviewCreationsPerHour(), Duration.ofHours(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Review limit reached. Please wait before starting more reviews.");
        }

        CodeReview review = new CodeReview();
        review.setUserId(userId);
        review.setRepositoryId(repo.getId());
        review.setType(request.type());
        review.setStatus(ReviewStatus.QUEUED);
        review.setTriggerSource(TriggerSource.MANUAL);

        if (request.type() == ReviewType.PULL_REQUEST) {
            if (request.pullRequestNumber() == null) {
                throw ApiException.badRequest("pullRequestNumber is required for pull request reviews");
            }
            String token = tokens.requireToken(userId);
            GitHubModels.Pull pull = github.getPullRequest(token, repo.getOwner(), repo.getName(), request.pullRequestNumber());
            PullRequestRecord pr = upsertPullRequest(repo.getId(), pull);
            review.setPullRequestId(pr.getId());
            review.setBranch(pull.head() == null ? null : pull.head().ref());
            review.setCommitSha(pull.head() == null ? null : pull.head().sha());
        } else {
            review.setBranch(request.branch() == null || request.branch().isBlank()
                    ? repo.getDefaultBranch() : request.branch());
        }

        // Avoid piling up identical in-flight reviews (double clicks, retries without a key).
        Optional<CodeReview> inFlight = findInFlight(userId, review);
        if (inFlight.isPresent()) {
            return summary(inFlight.get());
        }
        review = reviews.save(review);
        jobService.enqueue(review.getId(), idempotencyKey != null ? idempotencyKey : "review:" + review.getId());
        return summary(review);
    }

    private Optional<CodeReview> findInFlight(UUID userId, CodeReview candidate) {
        Specification<CodeReview> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("userId"), userId),
                cb.equal(root.get("repositoryId"), candidate.getRepositoryId()),
                cb.equal(root.get("type"), candidate.getType()),
                root.get("status").in(ReviewStatus.QUEUED, ReviewStatus.RUNNING),
                candidate.getPullRequestId() != null
                        ? cb.equal(root.get("pullRequestId"), candidate.getPullRequestId())
                        : cb.equal(root.get("branch"), candidate.getBranch()));
        return reviews.findAll(spec).stream().findFirst();
    }

    @Transactional
    public PullRequestRecord upsertPullRequest(UUID repositoryId, GitHubModels.Pull pull) {
        PullRequestRecord pr = pullRequests.findByRepositoryIdAndNumber(repositoryId, pull.number())
                .orElseGet(PullRequestRecord::new);
        pr.setRepositoryId(repositoryId);
        pr.setNumber(pull.number());
        pr.setTitle(pull.title() == null ? "(untitled)" : pull.title().substring(0, Math.min(pull.title().length(), 1000)));
        pr.setAuthor(pull.user() == null ? null : pull.user().login());
        pr.setState(pull.state());
        pr.setHeadSha(pull.head() == null ? null : pull.head().sha());
        pr.setHeadRef(pull.head() == null ? null : pull.head().ref());
        pr.setBaseRef(pull.base() == null ? null : pull.base().ref());
        pr.setHtmlUrl(pull.htmlUrl());
        return pullRequests.save(pr);
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public CodeReview requireOwned(UUID userId, UUID reviewId) {
        return reviews.findByIdAndUserId(reviewId, userId).orElseThrow(() -> ApiException.notFound("Review"));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewSummaryDto> list(UUID userId, UUID repositoryId, ReviewStatus status, String branch,
                                               Severity severity, Instant from, Instant to, int page, int size) {
        Specification<CodeReview> spec = (root, q, cb) -> cb.equal(root.get("userId"), userId);
        if (repositoryId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("repositoryId"), repositoryId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (branch != null && !branch.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("branch"), branch));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        if (severity != null) {
            // "has at least one finding at or above this severity"
            List<String> columns = Arrays.stream(Severity.values()).filter(s -> s.atLeast(severity))
                    .map(s -> s.name().toLowerCase() + "Count").toList();
            spec = spec.and((root, q, cb) -> cb.or(columns.stream()
                    .map(c -> cb.greaterThan(root.<Integer>get(c), 0))
                    .toArray(jakarta.persistence.criteria.Predicate[]::new)));
        }
        Page<CodeReview> result = reviews.findAll(spec, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, GitRepository> repos = loadRepos(result.getContent());
        Map<UUID, PullRequestRecord> prs = loadPrs(result.getContent());
        return PageResponse.of(result, r -> summary(r, repos.get(r.getRepositoryId()), prs.get(r.getPullRequestId())));
    }

    @Transactional(readOnly = true)
    public ReviewDetailDto detail(UUID userId, UUID reviewId) {
        CodeReview r = requireOwned(userId, reviewId);
        GitRepository repo = repositories.findById(r.getRepositoryId()).orElse(null);
        PullRequestRecord pr = r.getPullRequestId() == null ? null : pullRequests.findById(r.getPullRequestId()).orElse(null);
        List<String> notes = r.getNotes() == null ? List.of() : List.of(r.getNotes().split("\n"));
        return new ReviewDetailDto(summary(r, repo, pr), r.getSummary(), notes, r.getErrorMessage(), r.getAiProvider(),
                r.getAiModel(), r.getFilesConsidered(), r.getFilesAnalyzed(), r.getFilesSkipped(), r.getStartedAt(),
                repo == null ? null : repo.getHtmlUrl(), pr == null ? null : pr.getHtmlUrl(), SCORE_FORMULA);
    }

    @Transactional(readOnly = true)
    public ReviewStatusDto status(UUID userId, UUID reviewId) {
        CodeReview r = requireOwned(userId, reviewId);
        return new ReviewStatusDto(r.getId(), r.getStatus(), r.getAiStatus(), r.getStartedAt(), r.getCompletedAt(),
                r.getErrorMessage(), r.getStatus().isTerminal());
    }

    @Transactional
    public ReviewStatusDto cancel(UUID userId, UUID reviewId) {
        CodeReview r = requireOwned(userId, reviewId);
        if (r.getStatus().isTerminal()) {
            throw ApiException.conflict("Review is already " + r.getStatus().name().toLowerCase());
        }
        r.setStatus(ReviewStatus.CANCELLED);
        r.setCompletedAt(Instant.now());
        jobs.findByReviewId(reviewId).ifPresent(j -> {
            if (j.getStatus() == JobStatus.QUEUED) {
                j.setStatus(JobStatus.CANCELLED);
            }
        });
        return new ReviewStatusDto(r.getId(), r.getStatus(), r.getAiStatus(), r.getStartedAt(), r.getCompletedAt(),
                r.getErrorMessage(), true);
    }

    @Transactional(readOnly = true)
    public List<FindingDto> findings(UUID userId, UUID reviewId, Severity severity, String category, String filePath,
                                     String source, String status) {
        requireOwned(userId, reviewId);
        return findings.findByReviewId(reviewId).stream()
                .filter(f -> severity == null || f.getSeverity() == severity)
                .filter(f -> category == null || category.isBlank() || f.getCategory().name().equalsIgnoreCase(category))
                .filter(f -> filePath == null || filePath.isBlank() || f.getFilePath().equals(filePath))
                .filter(f -> source == null || source.isBlank() || f.getSource().name().equalsIgnoreCase(source))
                .filter(f -> status == null || status.isBlank() || f.getStatus().name().equalsIgnoreCase(status))
                .sorted(java.util.Comparator.comparing(ReviewFinding::getSeverity)
                        .thenComparing(ReviewFinding::getFilePath)
                        .thenComparing(f -> f.getStartLine() == null ? Integer.MAX_VALUE : f.getStartLine()))
                .map(FindingDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReviewFileDto> files(UUID userId, UUID reviewId) {
        requireOwned(userId, reviewId);
        Map<String, Long> counts = findings.findByReviewId(reviewId).stream()
                .collect(Collectors.groupingBy(ReviewFinding::getFilePath, Collectors.counting()));
        return files.findByReviewIdOrderByPath(reviewId).stream()
                .map(f -> new ReviewFileDto(f.getPath(), f.getLanguage(), f.isAnalyzed(), f.getSkipReason(),
                        f.getChangedLines(), counts.getOrDefault(f.getPath(), 0L)))
                .toList();
    }

    /** Fetches a file live from GitHub at the reviewed commit (source is not stored by CodeSage). */
    @Transactional(readOnly = true)
    public FileContentDto fileContent(UUID userId, UUID reviewId, String path) {
        CodeReview r = requireOwned(userId, reviewId);
        if (path == null || path.isBlank() || path.length() > 1000 || path.contains("..") || path.startsWith("/")) {
            throw ApiException.badRequest("Invalid file path");
        }
        if (files.findFirstByReviewIdAndPath(reviewId, path).isEmpty()) {
            throw ApiException.notFound("File in this review");
        }
        if (r.getCommitSha() == null) {
            throw ApiException.conflict("The review has not resolved a commit yet");
        }
        GitRepository repo = repositories.findById(r.getRepositoryId()).orElseThrow(() -> ApiException.notFound("Repository"));
        String content = github.getFileContent(tokens.requireToken(userId), repo.getOwner(), repo.getName(), path,
                r.getCommitSha());
        String language = Language.detect(path).map(Language::editorLanguage).orElse("plaintext");
        return new FileContentDto(path, language, content == null ? "" : content, r.getCommitSha());
    }

    // ---------------------------------------------------------------- comparison

    @Transactional(readOnly = true)
    public ComparisonDto compare(UUID userId, UUID reviewId, UUID baseReviewId) {
        CodeReview current = requireOwned(userId, reviewId);
        CodeReview base;
        if (baseReviewId != null) {
            base = requireOwned(userId, baseReviewId);
            if (!base.getRepositoryId().equals(current.getRepositoryId())) {
                throw ApiException.badRequest("Reviews must belong to the same repository to be compared");
            }
        } else {
            Specification<CodeReview> spec = (root, q, cb) -> cb.and(
                    cb.equal(root.get("userId"), userId),
                    cb.equal(root.get("repositoryId"), current.getRepositoryId()),
                    cb.equal(root.get("status"), ReviewStatus.COMPLETED),
                    cb.lessThan(root.get("createdAt"), current.getCreatedAt()),
                    cb.notEqual(root.get("id"), current.getId()));
            base = reviews.findAll(spec, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt")))
                    .stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("Earlier completed review of this repository"));
        }
        List<ReviewFinding> currentFindings = findings.findByReviewId(current.getId());
        List<ReviewFinding> baseFindings = findings.findByReviewId(base.getId());
        Set<String> baseFps = baseFindings.stream().map(ReviewFinding::getFingerprint).collect(Collectors.toSet());
        Set<String> currentFps = currentFindings.stream().map(ReviewFinding::getFingerprint).collect(Collectors.toSet());
        return new ComparisonDto(base.getId(), current.getId(), base.getCreatedAt(),
                currentFindings.stream().filter(f -> !baseFps.contains(f.getFingerprint())).map(FindingDto::from).toList(),
                baseFindings.stream().filter(f -> !currentFps.contains(f.getFingerprint())).map(FindingDto::from).toList(),
                currentFindings.stream().filter(f -> baseFps.contains(f.getFingerprint())).map(FindingDto::from).toList());
    }

    // ---------------------------------------------------------------- mapping

    public ReviewSummaryDto summary(CodeReview r) {
        GitRepository repo = repositories.findById(r.getRepositoryId()).orElse(null);
        PullRequestRecord pr = r.getPullRequestId() == null ? null : pullRequests.findById(r.getPullRequestId()).orElse(null);
        return summary(r, repo, pr);
    }

    public static ReviewSummaryDto summary(CodeReview r, GitRepository repo, PullRequestRecord pr) {
        return new ReviewSummaryDto(r.getId(), r.getRepositoryId(), repo == null ? null : repo.getFullName(), r.getType(),
                r.getBranch(), r.getCommitSha(), pr == null ? null : pr.getNumber(), pr == null ? null : pr.getTitle(),
                r.getStatus(), r.getTriggerSource(), r.getScore(), r.getAiStatus(),
                new SeverityCounts(r.getCriticalCount(), r.getHighCount(), r.getMediumCount(), r.getLowCount(),
                        r.getInfoCount()),
                r.totalFindings(), r.getCreatedAt(), r.getCompletedAt());
    }

    public Map<UUID, GitRepository> loadRepos(Collection<CodeReview> list) {
        Set<UUID> ids = list.stream().map(CodeReview::getRepositoryId).collect(Collectors.toSet());
        return repositories.findAllById(ids).stream().collect(Collectors.toMap(GitRepository::getId, Function.identity()));
    }

    public Map<UUID, PullRequestRecord> loadPrs(Collection<CodeReview> list) {
        Set<UUID> ids = list.stream().map(CodeReview::getPullRequestId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, PullRequestRecord> map = new HashMap<>();
        pullRequests.findAllById(ids).forEach(p -> map.put(p.getId(), p));
        return map;
    }
}
