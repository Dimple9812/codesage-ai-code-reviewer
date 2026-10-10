package com.codesage.repository;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public final class RepositoryDtos {

    private RepositoryDtos() {
    }

    public record RepositoryDto(UUID id, long githubId, String fullName, String owner, String name,
                                String description, String language, boolean isPrivate, String defaultBranch,
                                String htmlUrl, boolean autoReview, Instant pushedAt, Instant lastSyncedAt)
            implements Serializable {

        public static RepositoryDto from(GitRepository r) {
            return new RepositoryDto(r.getId(), r.getGithubId(), r.getFullName(), r.getOwner(), r.getName(),
                    r.getDescription(), r.getLanguage(), r.isPrivateRepo(), r.getDefaultBranch(), r.getHtmlUrl(),
                    r.isAutoReview(), r.getPushedAt(), r.getLastSyncedAt());
        }
    }

    public record BranchDto(String name, String sha, boolean isProtected) implements Serializable {
    }

    public record CommitDto(String sha, String message, String author, Instant date, String htmlUrl)
            implements Serializable {
    }

    public record PullRequestDto(int number, String title, String state, String author, String headRef,
                                 String headSha, String baseRef, String htmlUrl, Instant updatedAt, boolean draft)
            implements Serializable {
    }

    public record AutoReviewRequest(boolean enabled) {
    }
}
