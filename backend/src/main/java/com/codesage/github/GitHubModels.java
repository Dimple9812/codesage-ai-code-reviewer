package com.codesage.github;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Minimal views of GitHub REST API payloads - only the fields CodeSage needs. */
public final class GitHubModels {

    private GitHubModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(String login) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Permissions(boolean admin, boolean push, boolean pull) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Repo(long id, String name, String fullName, Owner owner, String description, String language,
                       @JsonProperty("private") boolean isPrivate, String defaultBranch, String htmlUrl,
                       Instant pushedAt, Permissions permissions, boolean archived) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShaRef(String sha) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Branch(String name, ShaRef commit, @JsonProperty("protected") boolean isProtected) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitAuthor(String name, Instant date) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitDetail(String message, CommitAuthor author) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Commit(String sha, CommitDetail commit, String htmlUrl, Owner author) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitRef(String ref, String sha, Repo repo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Pull(long id, int number, String title, String state, Owner user, GitRef head, GitRef base,
                       String htmlUrl, Instant updatedAt, boolean draft, Integer changedFiles,
                       Integer additions, Integer deletions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullFile(String sha, String filename, String status, int additions, int deletions, int changes,
                           String patch) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TreeEntry(String path, String type, Long size, String sha) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tree(String sha, boolean truncated, List<TreeEntry> tree) {
    }

    public record ReviewComment(String path, int line, String side, String body) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateReviewRequest(String commitId, String body, String event, List<ReviewComment> comments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreatedReview(long id, String htmlUrl, String state) {
    }
}
