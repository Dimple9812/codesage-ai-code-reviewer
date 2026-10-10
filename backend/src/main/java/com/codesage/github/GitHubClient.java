package com.codesage.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

import com.codesage.config.AppProperties;
import com.codesage.github.GitHubModels.Branch;
import com.codesage.github.GitHubModels.Commit;
import com.codesage.github.GitHubModels.CreateReviewRequest;
import com.codesage.github.GitHubModels.CreatedReview;
import com.codesage.github.GitHubModels.Pull;
import com.codesage.github.GitHubModels.PullFile;
import com.codesage.github.GitHubModels.Repo;
import com.codesage.github.GitHubModels.Tree;

/**
 * Thin, typed wrapper over the GitHub REST API. Calls are always made with the requesting user's own
 * token, so GitHub enforces repository permissions; CodeSage never escalates access.
 * Tokens are never logged.
 */
@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final int MAX_REPO_PAGES = 5;

    private final RestClient rest;
    private final String apiUrl;

    public GitHubClient(RestClient.Builder builder, AppProperties props) {
        this.apiUrl = trim(props.github().apiUrl());
        HttpClient http = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.getDefault()) // honour JVM proxy settings (https.proxyHost etc.)
                .connectTimeout(props.github().timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.github().timeout());
        this.rest = builder.clone()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "CodeSage-AI")
                .build();
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private URI uri(String path) {
        return URI.create(apiUrl + path);
    }

    private static String seg(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String path) {
        return Arrays.stream(path.split("/")).map(GitHubClient::seg).collect(Collectors.joining("/"));
    }

    private static String repoPath(String owner, String name) {
        return "/repos/" + seg(owner) + "/" + seg(name);
    }

    public List<Repo> listRepositories(String token) {
        List<Repo> all = new ArrayList<>();
        for (int page = 1; page <= MAX_REPO_PAGES; page++) {
            List<Repo> batch = get(token, "/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member&page=" + page,
                    new ParameterizedTypeReference<List<Repo>>() { });
            if (batch == null || batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < 100) {
                break;
            }
        }
        return all;
    }

    public Repo getRepository(String token, String owner, String name) {
        return get(token, repoPath(owner, name), new ParameterizedTypeReference<Repo>() { });
    }

    public List<Branch> listBranches(String token, String owner, String name) {
        return get(token, repoPath(owner, name) + "/branches?per_page=100",
                new ParameterizedTypeReference<List<Branch>>() { });
    }

    public Branch getBranch(String token, String owner, String name, String branch) {
        return get(token, repoPath(owner, name) + "/branches/" + encodePath(branch),
                new ParameterizedTypeReference<Branch>() { });
    }

    public List<Commit> listCommits(String token, String owner, String name, String branch, int limit) {
        String sha = branch == null ? "" : "&sha=" + UriUtils.encodeQueryParam(branch, StandardCharsets.UTF_8);
        return get(token, repoPath(owner, name) + "/commits?per_page=" + Math.min(limit, 100) + sha,
                new ParameterizedTypeReference<List<Commit>>() { });
    }

    public List<Pull> listPullRequests(String token, String owner, String name, String state) {
        return get(token, repoPath(owner, name) + "/pulls?per_page=50&sort=updated&direction=desc&state=" + seg(state),
                new ParameterizedTypeReference<List<Pull>>() { });
    }

    public Pull getPullRequest(String token, String owner, String name, int number) {
        return get(token, repoPath(owner, name) + "/pulls/" + number, new ParameterizedTypeReference<Pull>() { });
    }

    public List<PullFile> listPullRequestFiles(String token, String owner, String name, int number, int maxFiles) {
        List<PullFile> all = new ArrayList<>();
        for (int page = 1; page <= 10 && all.size() < maxFiles; page++) {
            List<PullFile> batch = get(token, repoPath(owner, name) + "/pulls/" + number + "/files?per_page=100&page=" + page,
                    new ParameterizedTypeReference<List<PullFile>>() { });
            if (batch == null || batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < 100) {
                break;
            }
        }
        return all;
    }

    public Tree getTree(String token, String owner, String name, String sha) {
        return get(token, repoPath(owner, name) + "/git/trees/" + seg(sha) + "?recursive=1",
                new ParameterizedTypeReference<Tree>() { });
    }

    /** Raw file content at a given commit/branch. Returns null for files GitHub will not serve raw (e.g. >100MB). */
    public String getFileContent(String token, String owner, String name, String path, String ref) {
        String url = repoPath(owner, name) + "/contents/" + encodePath(path) + "?ref="
                + UriUtils.encodeQueryParam(ref, StandardCharsets.UTF_8);
        try {
            byte[] body = rest.get().uri(uri(url))
                    .headers(h -> auth(h, token))
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json")
                    .retrieve()
                    .body(byte[].class);
            return body == null ? null : new String(body, StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            log.warn("GitHub request failed: {}", e.getClass().getSimpleName());
            throw GitHubApiException.unavailable();
        }
    }

    /** Publishes a COMMENT-only review. CodeSage never approves, requests changes, merges or closes PRs. */
    public CreatedReview createCommentReview(String token, String owner, String name, int number, CreateReviewRequest request) {
        if (!"COMMENT".equals(request.event())) {
            throw new IllegalArgumentException("Only COMMENT reviews are permitted");
        }
        try {
            return rest.post().uri(uri(repoPath(owner, name) + "/pulls/" + number + "/reviews"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(CreatedReview.class);
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw GitHubApiException.unavailable();
        }
    }

    private <T> T get(String token, String path, ParameterizedTypeReference<T> type) {
        try {
            return rest.get().uri(uri(path))
                    .headers(h -> auth(h, token))
                    .retrieve()
                    .body(type);
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            log.warn("GitHub request failed: {}", e.getClass().getSimpleName());
            throw GitHubApiException.unavailable();
        }
    }

    /** Adds the bearer token. A blank token means an anonymous call (public data only; used by local tooling). */
    private static void auth(HttpHeaders headers, String token) {
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }

    static GitHubApiException translate(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        HttpHeaders headers = e.getResponseHeaders();
        String remaining = headers == null ? null : headers.getFirst("x-ratelimit-remaining");
        if (status.value() == 401) {
            return GitHubApiException.reauth();
        }
        if (status.value() == 429 || (status.value() == 403 && "0".equals(remaining))) {
            return GitHubApiException.rateLimited();
        }
        if (status.value() == 403) {
            return GitHubApiException.forbidden();
        }
        if (status.value() == 404) {
            return GitHubApiException.notFound();
        }
        if (status.value() == 422) {
            return GitHubApiException.unprocessable("GitHub rejected the request (some comment lines may be outside the diff).");
        }
        log.warn("GitHub returned HTTP {}", status.value());
        return GitHubApiException.unavailable();
    }
}
