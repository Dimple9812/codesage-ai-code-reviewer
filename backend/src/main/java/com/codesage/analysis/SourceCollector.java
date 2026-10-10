package com.codesage.analysis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.codesage.config.AppProperties;
import com.codesage.github.GitHubApiException;
import com.codesage.github.GitHubClient;
import com.codesage.github.GitHubModels;

/** Retrieves a bounded set of reviewable files from GitHub, respecting configured size and count limits. */
@Component
public class SourceCollector {

    public record SkippedFile(String path, Language language, Integer size, String reason) {
    }

    public record Collection(List<SourceFile> files, List<SkippedFile> skipped, int considered, List<String> notes) {
    }

    private final GitHubClient github;
    private final AppProperties props;

    public SourceCollector(GitHubClient github, AppProperties props) {
        this.github = github;
        this.props = props;
    }

    public Collection collectRepository(String token, String owner, String repo, String sha,
                                        Set<Language> languages, int maxFilesSetting) {
        GitHubModels.Tree tree = github.getTree(token, owner, repo, sha);
        List<String> notes = new ArrayList<>();
        if (tree.truncated()) {
            notes.add("GitHub truncated the repository tree; only part of the repository was considered.");
        }
        int maxFiles = Math.min(maxFilesSetting, props.review().maxFiles());
        List<SkippedFile> skipped = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        int considered = 0;
        for (GitHubModels.TreeEntry entry : tree.tree() == null ? List.<GitHubModels.TreeEntry>of() : tree.tree()) {
            if (!"blob".equals(entry.type())) {
                continue;
            }
            var language = Language.detect(entry.path());
            var reason = FileFilter.exclusionReason(entry.path());
            if (reason.isPresent()) {
                // Unsupported types are silently ignored; excluded supported files are reported.
                if (language.isPresent()) {
                    considered++;
                    skipped.add(new SkippedFile(entry.path(), language.get(), size(entry), reason.get()));
                }
                continue;
            }
            considered++;
            if (!languages.contains(language.get())) {
                skipped.add(new SkippedFile(entry.path(), language.get(), size(entry), "Language disabled in settings"));
                continue;
            }
            if (entry.size() != null && entry.size() > props.review().maxFileBytes()) {
                skipped.add(new SkippedFile(entry.path(), language.get(), size(entry), "File exceeds size limit"));
                continue;
            }
            candidates.add(new Candidate(entry.path(), language.get(), entry.size() == null ? 0 : entry.size()));
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> FileFilter.priority(c.path(), c.language()))
                .thenComparing(Candidate::path));

        List<SourceFile> files = new ArrayList<>();
        long totalBytes = 0;
        for (Candidate c : candidates) {
            if (files.size() >= maxFiles) {
                skipped.add(new SkippedFile(c.path(), c.language(), (int) c.size(), "File count limit reached"));
                continue;
            }
            if (totalBytes + c.size() > props.review().maxTotalBytes()) {
                skipped.add(new SkippedFile(c.path(), c.language(), (int) c.size(), "Total size limit reached"));
                continue;
            }
            String content = fetch(token, owner, repo, c.path(), sha);
            if (content == null || FileFilter.looksBinary(content) || FileFilter.looksGenerated(content)) {
                skipped.add(new SkippedFile(c.path(), c.language(), (int) c.size(), "Binary or generated content"));
                continue;
            }
            totalBytes += content.getBytes(StandardCharsets.UTF_8).length;
            files.add(new SourceFile(c.path(), c.language(), content, Set.of(), Set.of()));
        }
        long limited = skipped.stream().filter(s -> s.reason().endsWith("limit reached")).count();
        if (limited > 0) {
            notes.add(limited + " file(s) were not reviewed because of the configured file-count or size limits.");
        }
        return new Collection(files, skipped, considered, notes);
    }

    public Collection collectPullRequest(String token, String owner, String repo, int number, String headSha,
                                         Set<Language> languages, int maxFilesSetting) {
        int maxFiles = Math.min(maxFilesSetting, props.review().maxFiles());
        List<GitHubModels.PullFile> prFiles = github.listPullRequestFiles(token, owner, repo, number, 1000);
        List<SourceFile> files = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int considered = 0;
        long totalBytes = 0;
        for (GitHubModels.PullFile pf : prFiles) {
            var language = Language.detect(pf.filename());
            if (language.isEmpty()) {
                continue;
            }
            considered++;
            if ("removed".equals(pf.status())) {
                skipped.add(new SkippedFile(pf.filename(), language.get(), null, "File deleted in this pull request"));
                continue;
            }
            var reason = FileFilter.exclusionReason(pf.filename());
            if (reason.isPresent()) {
                skipped.add(new SkippedFile(pf.filename(), language.get(), null, reason.get()));
                continue;
            }
            if (!languages.contains(language.get())) {
                skipped.add(new SkippedFile(pf.filename(), language.get(), null, "Language disabled in settings"));
                continue;
            }
            if (pf.patch() == null) {
                skipped.add(new SkippedFile(pf.filename(), language.get(), null, "Diff not available (binary or too large)"));
                continue;
            }
            if (files.size() >= maxFiles) {
                skipped.add(new SkippedFile(pf.filename(), language.get(), null, "File count limit reached"));
                continue;
            }
            String content = fetch(token, owner, repo, pf.filename(), headSha);
            if (content == null || FileFilter.looksBinary(content) || FileFilter.looksGenerated(content)) {
                skipped.add(new SkippedFile(pf.filename(), language.get(), null, "Binary or generated content"));
                continue;
            }
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > props.review().maxFileBytes() || totalBytes + bytes > props.review().maxTotalBytes()) {
                skipped.add(new SkippedFile(pf.filename(), language.get(), bytes, "File exceeds size limit"));
                continue;
            }
            totalBytes += bytes;
            DiffParser.DiffLines diff = DiffParser.parse(pf.patch());
            files.add(new SourceFile(pf.filename(), language.get(), content, diff.added(), diff.visible()));
        }
        if (prFiles.size() >= 1000) {
            notes.add("The pull request has more files than GitHub returns; only the first 1000 were considered.");
        }
        return new Collection(files, skipped, considered, notes);
    }

    private String fetch(String token, String owner, String repo, String path, String ref) {
        try {
            return github.getFileContent(token, owner, repo, path, ref);
        } catch (GitHubApiException e) {
            if ("GITHUB_NOT_FOUND".equals(e.getCode())) {
                return null;
            }
            throw e;
        }
    }

    private static Integer size(GitHubModels.TreeEntry entry) {
        return entry.size() == null ? null : entry.size().intValue();
    }

    private record Candidate(String path, Language language, long size) {
    }
}
