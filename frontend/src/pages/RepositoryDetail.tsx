import { ExternalLink, GitCommitHorizontal, GitPullRequest, Lock } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";

import { useBranches, useCommits, usePullRequests, useRepository, useReviews, useSetAutoReview } from "../api/hooks";
import { StartReviewForm } from "../components/StartReview";
import { ErrorState, inputClass, LoadingBlock, PageHeader, Panel } from "../components/ui";
import { relativeTime, shortSha } from "../lib/format";
import { ReviewRow } from "./Dashboard";

export default function RepositoryDetail() {
  const { repositoryId = "" } = useParams();
  const repo = useRepository(repositoryId);
  const [branch, setBranch] = useState<string | undefined>();
  const [prForReview, setPrForReview] = useState<number | undefined>();
  const branches = useBranches(repositoryId);
  const activeBranch = branch ?? repo.data?.defaultBranch ?? undefined;
  const commits = useCommits(repositoryId, activeBranch);
  const pulls = usePullRequests(repositoryId);
  const reviews = useReviews({ repositoryId, size: 5 });
  const autoReview = useSetAutoReview(repositoryId);

  if (repo.isLoading) return <LoadingBlock rows={6} />;
  if (repo.error || !repo.data) return <ErrorState error={repo.error} onRetry={() => repo.refetch()} />;
  const r = repo.data;

  return (
    <div className="mx-auto max-w-6xl">
      <nav className="mb-3 text-sm text-muted">
        <Link to="/repositories" className="hover:text-ink">
          Repositories
        </Link>{" "}
        / <span className="text-ink">{r.name}</span>
      </nav>
      <PageHeader
        title={r.fullName}
        description={
          <span className="flex flex-wrap items-center gap-3">
            {r.isPrivate && (
              <span className="inline-flex items-center gap-1">
                <Lock className="size-3.5" aria-hidden /> Private
              </span>
            )}
            {r.language && <span>{r.language}</span>}
            {r.description && <span>{r.description}</span>}
          </span>
        }
        actions={
          r.htmlUrl && (
            <a href={r.htmlUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-ink">
              Open on GitHub <ExternalLink className="size-3.5" aria-hidden />
            </a>
          )
        }
      />

      <div className="grid gap-6 lg:grid-cols-[1fr_22rem]">
        <div className="space-y-6">
          <Panel>
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-3">
              <h2 className="font-semibold">Recent commits</h2>
              <label htmlFor="commit-branch" className="sr-only">
                Branch
              </label>
              <select
                id="commit-branch"
                className={`${inputClass} w-auto py-1.5`}
                value={activeBranch ?? ""}
                onChange={(e) => setBranch(e.target.value)}
                disabled={!branches.data}
              >
                {(branches.data ?? []).map((b) => (
                  <option key={b.name}>{b.name}</option>
                ))}
              </select>
            </div>
            {commits.isLoading ? (
              <LoadingBlock rows={4} label="Loading commits" />
            ) : commits.error ? (
              <div className="p-5">
                <ErrorState error={commits.error} onRetry={() => commits.refetch()} />
              </div>
            ) : (commits.data ?? []).length === 0 ? (
              <p className="p-5 text-sm text-muted">No commits on this branch.</p>
            ) : (
              <ul className="divide-y divide-line">
                {commits.data!.slice(0, 10).map((c) => (
                  <li key={c.sha} className="flex items-start gap-3 px-5 py-3">
                    <GitCommitHorizontal className="mt-0.5 size-4 shrink-0 text-faint" aria-hidden />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm">{c.message}</p>
                      <p className="text-xs text-muted">
                        {c.author ?? "unknown"} · {relativeTime(c.date)}
                      </p>
                    </div>
                    <a href={c.htmlUrl ?? undefined} target="_blank" rel="noopener noreferrer" className="font-mono text-xs text-muted hover:text-ink">
                      {shortSha(c.sha)}
                    </a>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel>
            <h2 className="border-b border-line px-5 py-3 font-semibold">Open pull requests</h2>
            {pulls.isLoading ? (
              <LoadingBlock rows={3} label="Loading pull requests" />
            ) : pulls.error ? (
              <div className="p-5">
                <ErrorState error={pulls.error} onRetry={() => pulls.refetch()} />
              </div>
            ) : (pulls.data ?? []).length === 0 ? (
              <p className="p-5 text-sm text-muted">No open pull requests.</p>
            ) : (
              <ul className="divide-y divide-line">
                {pulls.data!.map((p) => (
                  <li key={p.number} className="flex items-center gap-3 px-5 py-3">
                    <GitPullRequest className="size-4 shrink-0 text-sage" aria-hidden />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm">
                        <span className="text-muted">#{p.number}</span> {p.title}
                        {p.draft && <span className="ml-2 text-xs text-faint">draft</span>}
                      </p>
                      <p className="text-xs text-muted">
                        {p.author} · <span className="font-mono">{p.headRef}</span> → <span className="font-mono">{p.baseRef}</span> ·{" "}
                        {relativeTime(p.updatedAt)}
                      </p>
                    </div>
                    <button onClick={() => setPrForReview(p.number)} className="rounded-md border border-line px-2.5 py-1 text-xs hover:border-sage hover:text-sage">
                      Review
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel>
            <div className="flex items-center justify-between border-b border-line px-5 py-3">
              <h2 className="font-semibold">Past reviews</h2>
              <Link to={`/reviews?repositoryId=${r.id}`} className="text-sm text-sage hover:underline">
                Full history
              </Link>
            </div>
            {reviews.isLoading ? (
              <LoadingBlock rows={3} />
            ) : (reviews.data?.content ?? []).length === 0 ? (
              <p className="p-5 text-sm text-muted">This repository has not been reviewed yet.</p>
            ) : (
              <ul className="divide-y divide-line">
                {reviews.data!.content.map((rv) => (
                  <ReviewRow key={rv.id} review={rv} />
                ))}
              </ul>
            )}
          </Panel>
        </div>

        <div className="space-y-6">
          <Panel className="p-5">
            <h2 className="mb-4 font-semibold">Start a review</h2>
            <StartReviewForm key={prForReview ?? "branch"} repository={r} initialPr={prForReview} />
          </Panel>
          <Panel className="p-5">
            <h2 className="font-semibold">Automatic pull request reviews</h2>
            <p className="mt-2 text-sm text-muted">
              When on, pull requests opened, reopened or updated in this repository are reviewed automatically through the GitHub
              webhook. The webhook must be added in the repository settings (see README).
            </p>
            <label className="mt-4 flex cursor-pointer items-center gap-3 text-sm">
              <input
                type="checkbox"
                className="size-4 accent-[var(--color-sage)]"
                checked={r.autoReview}
                disabled={autoReview.isPending}
                onChange={(e) => autoReview.mutate(e.target.checked)}
              />
              Review new pull requests automatically
            </label>
          </Panel>
        </div>
      </div>
    </div>
  );
}
