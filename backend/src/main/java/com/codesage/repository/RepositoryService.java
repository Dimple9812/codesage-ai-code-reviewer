package com.codesage.repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codesage.exception.ApiException;
import com.codesage.github.GitHubClient;
import com.codesage.github.GitHubModels;
import com.codesage.github.GitHubTokenService;
import com.codesage.repository.RepositoryDtos.BranchDto;
import com.codesage.repository.RepositoryDtos.CommitDto;
import com.codesage.repository.RepositoryDtos.PullRequestDto;
import com.codesage.repository.RepositoryDtos.RepositoryDto;

@Service
public class RepositoryService {

    private static final Duration SYNC_TTL = Duration.ofMinutes(5);

    private final GitRepositoryRepository repositories;
    private final GitHubClient github;
    private final GitHubTokenService tokens;
    private final Clock clock;

    public RepositoryService(GitRepositoryRepository repositories, GitHubClient github,
                             GitHubTokenService tokens, Clock clock) {
        this.repositories = repositories;
        this.github = github;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Lists repositories the user can access on GitHub (synced into metadata rows, never source). */
    @Transactional
    public List<RepositoryDto> list(UUID userId, String query, String language, boolean refresh) {
        List<GitRepository> existing = repositories.findByUserIdOrderByPushedAtDesc(userId);
        boolean stale = existing.isEmpty() || existing.stream()
                .map(GitRepository::getLastSyncedAt)
                .min(Instant::compareTo)
                .map(t -> t.isBefore(clock.instant().minus(SYNC_TTL)))
                .orElse(true);
        if (refresh || stale) {
            sync(userId);
            existing = repositories.findByUserIdOrderByPushedAtDesc(userId);
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return existing.stream()
                .filter(r -> q.isEmpty()
                        || r.getFullName().toLowerCase(Locale.ROOT).contains(q)
                        || (r.getDescription() != null && r.getDescription().toLowerCase(Locale.ROOT).contains(q)))
                .filter(r -> language == null || language.isBlank() || language.equalsIgnoreCase(r.getLanguage()))
                .map(RepositoryDto::from)
                .toList();
    }

    private void sync(UUID userId) {
        String token = tokens.requireToken(userId);
        Instant now = clock.instant();
        for (GitHubModels.Repo repo : github.listRepositories(token)) {
            GitRepository row = repositories.findByUserIdAndGithubId(userId, repo.id()).orElseGet(GitRepository::new);
            apply(row, repo, userId, now);
            repositories.save(row);
        }
    }

    static void apply(GitRepository row, GitHubModels.Repo repo, UUID userId, Instant now) {
        row.setUserId(userId);
        row.setGithubId(repo.id());
        row.setOwner(repo.owner() != null ? repo.owner().login() : repo.fullName().split("/")[0]);
        row.setName(repo.name());
        row.setFullName(repo.fullName());
        row.setDescription(repo.description() == null ? null
                : repo.description().substring(0, Math.min(repo.description().length(), 2000)));
        row.setLanguage(repo.language());
        row.setPrivateRepo(repo.isPrivate());
        row.setDefaultBranch(repo.defaultBranch());
        row.setHtmlUrl(repo.htmlUrl());
        row.setPushedAt(repo.pushedAt());
        row.setLastSyncedAt(now);
    }

    /** Ownership check: returns 404 (not 403) so other users' resource ids are not disclosed. */
    @Transactional(readOnly = true)
    public GitRepository requireOwned(UUID userId, UUID repositoryId) {
        return repositories.findByIdAndUserId(repositoryId, userId)
                .orElseThrow(() -> ApiException.notFound("Repository"));
    }

    public RepositoryDto get(UUID userId, UUID repositoryId) {
        return RepositoryDto.from(requireOwned(userId, repositoryId));
    }

    @Cacheable(cacheNames = "github-branches", key = "#userId + ':' + #repositoryId")
    public List<BranchDto> branches(UUID userId, UUID repositoryId) {
        GitRepository repo = requireOwned(userId, repositoryId);
        return github.listBranches(tokens.requireToken(userId), repo.getOwner(), repo.getName()).stream()
                .map(b -> new BranchDto(b.name(), b.commit() == null ? null : b.commit().sha(), b.isProtected()))
                .toList();
    }

    @Cacheable(cacheNames = "github-commits", key = "#userId + ':' + #repositoryId + ':' + #branch")
    public List<CommitDto> commits(UUID userId, UUID repositoryId, String branch) {
        GitRepository repo = requireOwned(userId, repositoryId);
        String ref = branch == null || branch.isBlank() ? repo.getDefaultBranch() : branch;
        return github.listCommits(tokens.requireToken(userId), repo.getOwner(), repo.getName(), ref, 20).stream()
                .map(c -> new CommitDto(c.sha(),
                        c.commit() == null ? "" : firstLine(c.commit().message()),
                        c.commit() != null && c.commit().author() != null ? c.commit().author().name() : null,
                        c.commit() != null && c.commit().author() != null ? c.commit().author().date() : null,
                        c.htmlUrl()))
                .toList();
    }

    @Cacheable(cacheNames = "github-pulls", key = "#userId + ':' + #repositoryId + ':' + #state")
    public List<PullRequestDto> pullRequests(UUID userId, UUID repositoryId, String state) {
        GitRepository repo = requireOwned(userId, repositoryId);
        String s = switch (state == null ? "open" : state.toLowerCase(Locale.ROOT)) {
            case "closed" -> "closed";
            case "all" -> "all";
            default -> "open";
        };
        return github.listPullRequests(tokens.requireToken(userId), repo.getOwner(), repo.getName(), s).stream()
                .map(RepositoryService::toDto)
                .toList();
    }

    public static PullRequestDto toDto(GitHubModels.Pull p) {
        return new PullRequestDto(p.number(), p.title(), p.state(), p.user() == null ? null : p.user().login(),
                p.head() == null ? null : p.head().ref(), p.head() == null ? null : p.head().sha(),
                p.base() == null ? null : p.base().ref(), p.htmlUrl(), p.updatedAt(), p.draft());
    }

    @Transactional
    public RepositoryDto setAutoReview(UUID userId, UUID repositoryId, boolean enabled) {
        GitRepository repo = requireOwned(userId, repositoryId);
        repo.setAutoReview(enabled);
        return RepositoryDto.from(repositories.save(repo));
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
