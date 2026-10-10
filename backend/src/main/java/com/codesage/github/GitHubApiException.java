package com.codesage.github;

import org.springframework.http.HttpStatus;

import com.codesage.exception.ApiException;

public class GitHubApiException extends ApiException {

    public GitHubApiException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static GitHubApiException reauth() {
        return new GitHubApiException(HttpStatus.UNAUTHORIZED, "GITHUB_REAUTH_REQUIRED",
                "Your GitHub authorization is missing, expired or was revoked. Please sign in with GitHub again.");
    }

    public static GitHubApiException rateLimited() {
        return new GitHubApiException(HttpStatus.TOO_MANY_REQUESTS, "GITHUB_RATE_LIMITED",
                "GitHub API rate limit reached. Please try again later.");
    }

    public static GitHubApiException notFound() {
        return new GitHubApiException(HttpStatus.NOT_FOUND, "GITHUB_NOT_FOUND",
                "The GitHub resource was not found or you do not have access to it.");
    }

    public static GitHubApiException forbidden() {
        return new GitHubApiException(HttpStatus.FORBIDDEN, "GITHUB_FORBIDDEN",
                "GitHub denied access to this resource with the current authorization.");
    }

    public static GitHubApiException unavailable() {
        return new GitHubApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE",
                "GitHub is currently unavailable or returned an unexpected error. Please try again.");
    }

    public static GitHubApiException unprocessable(String message) {
        return new GitHubApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GITHUB_REJECTED", message);
    }
}
