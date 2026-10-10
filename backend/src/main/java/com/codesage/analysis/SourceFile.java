package com.codesage.analysis;

import java.util.List;
import java.util.Set;

/**
 * An in-memory source file fetched from GitHub for the duration of one review.
 * Content is never persisted (only bounded snippets attached to findings).
 *
 * @param changedLines new-side line numbers added/modified in a PR (empty for repository reviews)
 * @param diffLines    new-side line numbers visible in the PR diff hunks (commentable on GitHub)
 */
public record SourceFile(String path, Language language, String content, Set<Integer> changedLines,
                         Set<Integer> diffLines) {

    public List<String> lines() {
        return content.lines().toList();
    }

    public int lineCount() {
        return (int) content.lines().count();
    }

    public boolean isPullRequestFile() {
        return !changedLines.isEmpty() || !diffLines.isEmpty();
    }
}
