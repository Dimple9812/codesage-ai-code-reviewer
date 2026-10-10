package com.codesage.review;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.codesage.ai.AiReviewService;
import com.codesage.ai.LlmProviderFactory;
import com.codesage.analysis.DiffParser;
import com.codesage.analysis.FindingCandidate;
import com.codesage.analysis.Language;
import com.codesage.analysis.SourceCollector;
import com.codesage.analysis.SourceFile;
import com.codesage.analysis.StaticAnalyzer;
import com.codesage.exception.ApiException;
import com.codesage.findings.FindingNormalizer;
import com.codesage.findings.ReviewFinding;
import com.codesage.findings.Severity;
import com.codesage.github.GitHubClient;
import com.codesage.github.GitHubModels;
import com.codesage.github.GitHubTokenService;
import com.codesage.pullrequest.PullRequestRecord;
import com.codesage.pullrequest.PullRequestRecordRepository;
import com.codesage.repository.GitRepository;
import com.codesage.repository.GitRepositoryRepository;
import com.codesage.settings.SettingsService;
import com.codesage.settings.UserSettings;

/**
 * Orchestrates one review: resolve commit, fetch bounded sources, run static analyzers, run the AI
 * review, normalise findings, compute the score and persist. Source code only lives in memory here.
 */
@Component
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

    private final CodeReviewRepository reviews;
    private final GitRepositoryRepository repositories;
    private final PullRequestRecordRepository pullRequests;
    private final GitHubTokenService tokens;
    private final GitHubClient github;
    private final SourceCollector collector;
    private final List<StaticAnalyzer> analyzers;
    private final AiReviewService ai;
    private final LlmProviderFactory llmFactory;
    private final SettingsService settingsService;
    private final ReviewStateService state;

    public ReviewPipeline(CodeReviewRepository reviews, GitRepositoryRepository repositories,
                          PullRequestRecordRepository pullRequests, GitHubTokenService tokens, GitHubClient github,
                          SourceCollector collector, List<StaticAnalyzer> analyzers, AiReviewService ai,
                          LlmProviderFactory llmFactory, SettingsService settingsService, ReviewStateService state) {
        this.reviews = reviews;
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.tokens = tokens;
        this.github = github;
        this.collector = collector;
        this.analyzers = analyzers;
        this.ai = ai;
        this.llmFactory = llmFactory;
        this.settingsService = settingsService;
        this.state = state;
    }

    public void run(UUID reviewId) {
        if (!state.markRunning(reviewId)) {
            return;
        }
        CodeReview review = reviews.findById(reviewId).orElseThrow(() -> ApiException.notFound("Review"));
        GitRepository repo = repositories.findById(review.getRepositoryId())
                .orElseThrow(() -> ApiException.notFound("Repository"));
        UserSettings settings = settingsService.getOrCreate(review.getUserId());
        Set<String> enabledAnalyzers = SettingsService.csv(settings.getEnabledAnalyzers());
        Set<Language> languages = SettingsService.languages(settings);
        String token = tokens.requireToken(review.getUserId());

        // 1. Resolve the exact commit so findings, file views and published comments all match.
        SourceCollector.Collection collection;
        String reviewKind = review.getType().name();
        if (review.getType() == ReviewType.PULL_REQUEST) {
            PullRequestRecord pr = pullRequests.findById(review.getPullRequestId())
                    .orElseThrow(() -> ApiException.notFound("Pull request"));
            GitHubModels.Pull pull = github.getPullRequest(token, repo.getOwner(), repo.getName(), pr.getNumber());
            String headSha = pull.head().sha();
            state.recordCommit(reviewId, headSha, pull.head().ref());
            collection = collector.collectPullRequest(token, repo.getOwner(), repo.getName(), pr.getNumber(), headSha,
                    languages, settings.getMaxFiles());
        } else {
            String branch = review.getBranch() != null ? review.getBranch() : repo.getDefaultBranch();
            GitHubModels.Branch b = github.getBranch(token, repo.getOwner(), repo.getName(), branch);
            String sha = b.commit().sha();
            state.recordCommit(reviewId, sha, branch);
            collection = collector.collectRepository(token, repo.getOwner(), repo.getName(), sha, languages,
                    settings.getMaxFiles());
        }
        if (state.isCancelled(reviewId)) {
            return;
        }

        List<SourceFile> files = collection.files();
        List<String> notes = new ArrayList<>(collection.notes());
        List<FindingCandidate> candidates = new ArrayList<>();

        // 2. Deterministic static analysis.
        for (StaticAnalyzer analyzer : analyzers) {
            if (analyzer.isEnabledGlobally() && enabledAnalyzers.contains(analyzer.id())) {
                try {
                    candidates.addAll(analyzer.analyze(files));
                } catch (RuntimeException e) {
                    log.warn("Analyzer {} failed: {}", analyzer.id(), e.getMessage());
                    notes.add("Static analyzer '" + analyzer.id() + "' failed and was skipped.");
                }
            }
        }
        if (state.isCancelled(reviewId)) {
            return;
        }

        // 3. AI review (optional; never blocks static results).
        AiReviewService.Outcome outcome;
        if (!enabledAnalyzers.contains("ai")) {
            outcome = AiReviewService.Outcome.skipped(AiStatus.SKIPPED_DISABLED, "AI review is disabled in settings.");
        } else {
            var llm = llmFactory.resolve(settings);
            if (llm.isEmpty()) {
                outcome = AiReviewService.Outcome.skipped(AiStatus.SKIPPED_NOT_CONFIGURED,
                        "AI review skipped: no AI provider API key is configured. Add one in Settings or set AI_API_KEY on the server.");
            } else {
                outcome = ai.review(llm.get(), repo.getFullName(), reviewKind, files, () -> state.isCancelled(reviewId));
            }
        }
        candidates.addAll(outcome.findings());
        notes.addAll(outcome.notes());

        // 4. Normalise + persist.
        Map<String, SourceFile> byPath = new HashMap<>();
        files.forEach(f -> byPath.put(f.path(), f));
        Severity minSeverity = Severity.valueOf(settings.getMinSeverity());
        List<ReviewFinding> findings = FindingNormalizer.normalize(reviewId, candidates, byPath, minSeverity);
        if (review.getType() == ReviewType.PULL_REQUEST) {
            // For PR reviews keep findings on changed lines, plus file-level findings without a line.
            findings.removeIf(f -> {
                SourceFile sf = byPath.get(f.getFilePath());
                return sf != null && f.getStartLine() != null && !overlapsChanged(sf, f);
            });
        }

        List<ReviewFile> reviewFiles = new ArrayList<>();
        for (SourceFile f : files) {
            ReviewFile rf = new ReviewFile();
            rf.setReviewId(reviewId);
            rf.setPath(f.path());
            rf.setLanguage(f.language().id());
            rf.setSizeBytes(f.content().length());
            rf.setAnalyzed(true);
            if (!f.changedLines().isEmpty()) {
                rf.setChangedLines(DiffParser.toRanges(f.changedLines(), 3900));
            }
            reviewFiles.add(rf);
        }
        for (SourceCollector.SkippedFile s : collection.skipped().stream().limit(300).toList()) {
            ReviewFile rf = new ReviewFile();
            rf.setReviewId(reviewId);
            rf.setPath(s.path());
            rf.setLanguage(s.language() == null ? null : s.language().id());
            rf.setSizeBytes(s.size());
            rf.setAnalyzed(false);
            rf.setSkipReason(s.reason());
            reviewFiles.add(rf);
        }
        String summary = outcome.summary();
        if (summary == null) {
            summary = files.isEmpty()
                    ? "No reviewable source files were found for the selected scope."
                    : "Static analysis reviewed " + files.size() + " file(s) and reported " + findings.size() + " finding(s).";
        }
        state.complete(reviewId, findings, reviewFiles, collection.considered(), files.size(),
                collection.skipped().size(), outcome.status(), outcome.provider(), outcome.model(), summary, notes);
    }

    private static boolean overlapsChanged(SourceFile file, ReviewFinding f) {
        int end = f.getEndLine() == null ? f.getStartLine() : f.getEndLine();
        for (int n = f.getStartLine(); n <= end; n++) {
            if (file.changedLines().contains(n)) {
                return true;
            }
        }
        return false;
    }
}
