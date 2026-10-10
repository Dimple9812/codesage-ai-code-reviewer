import clsx from "clsx";
import { GitBranch, GitPullRequest, Play } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router";
import { toast } from "sonner";

import type { ApiError } from "../api/client";
import { useBranches, useCreateReview, usePullRequests, useRepositories } from "../api/hooks";
import type { Repository } from "../api/types";
import { newIdempotencyKey } from "../lib/format";
import { Button, ErrorState, Field, inputClass, LoadingBlock } from "./ui";

/** Form to queue a branch or pull request review for one repository. */
export function StartReviewForm({ repository, initialPr }: { repository: Repository; initialPr?: number }) {
  const [mode, setMode] = useState<"branch" | "pr">(initialPr ? "pr" : "branch");
  const [branch, setBranch] = useState(repository.defaultBranch ?? "");
  const [pr, setPr] = useState<number | undefined>(initialPr);
  const key = useMemo(() => newIdempotencyKey(), [mode, branch, pr]); // eslint-disable-line react-hooks/exhaustive-deps
  const branches = useBranches(repository.id);
  const pulls = usePullRequests(repository.id);
  const create = useCreateReview();
  const navigate = useNavigate();

  function submit() {
    const body =
      mode === "branch"
        ? { repositoryId: repository.id, type: "REPOSITORY" as const, branch: branch || undefined }
        : { repositoryId: repository.id, type: "PULL_REQUEST" as const, pullRequestNumber: pr };
    create.mutate(
      { body, key },
      {
        onSuccess: (review) => {
          toast.success("Review queued");
          navigate(`/reviews/${review.id}`);
        },
        onError: (e) => toast.error((e as ApiError).message),
      },
    );
  }

  return (
    <div className="space-y-5">
      <div role="tablist" aria-label="What to review" className="inline-flex rounded-md border border-line bg-night p-1">
        {(
          [
            ["branch", "Branch", GitBranch],
            ["pr", "Pull request", GitPullRequest],
          ] as const
        ).map(([id, label, Icon]) => (
          <button
            key={id}
            role="tab"
            aria-selected={mode === id}
            onClick={() => setMode(id)}
            className={clsx(
              "inline-flex items-center gap-2 rounded px-3 py-1.5 text-sm",
              mode === id ? "bg-raised text-ink" : "text-muted hover:text-ink",
            )}
          >
            <Icon className="size-4" aria-hidden /> {label}
          </button>
        ))}
      </div>

      {mode === "branch" ? (
        <Field label="Branch" htmlFor="branch" hint="CodeSage reviews the latest commit on this branch.">
          {branches.isLoading ? (
            <LoadingBlock rows={1} label="Loading branches" />
          ) : branches.error ? (
            <ErrorState error={branches.error} onRetry={() => branches.refetch()} />
          ) : (
            <select id="branch" className={inputClass} value={branch} onChange={(e) => setBranch(e.target.value)}>
              {(branches.data ?? []).map((b) => (
                <option key={b.name} value={b.name}>
                  {b.name}
                  {b.name === repository.defaultBranch ? " (default)" : ""}
                </option>
              ))}
            </select>
          )}
        </Field>
      ) : (
        <Field label="Open pull request" htmlFor="pr" hint="Only the added and modified lines are reviewed.">
          {pulls.isLoading ? (
            <LoadingBlock rows={1} label="Loading pull requests" />
          ) : pulls.error ? (
            <ErrorState error={pulls.error} onRetry={() => pulls.refetch()} />
          ) : (pulls.data ?? []).length === 0 ? (
            <p className="text-sm text-muted">This repository has no open pull requests.</p>
          ) : (
            <select id="pr" className={inputClass} value={pr ?? ""} onChange={(e) => setPr(Number(e.target.value) || undefined)}>
              <option value="">Choose a pull request</option>
              {(pulls.data ?? []).map((p) => (
                <option key={p.number} value={p.number}>
                  #{p.number} {p.title}
                </option>
              ))}
            </select>
          )}
        </Field>
      )}

      <Button variant="primary" onClick={submit} loading={create.isPending} disabled={mode === "pr" ? !pr : !branch}>
        <Play className="size-4" aria-hidden /> Start review
      </Button>
    </div>
  );
}

/** Repository picker + form, used for the dashboard quick action. */
export function QuickStartReview() {
  const { data, isLoading, error, refetch } = useRepositories();
  const [repoId, setRepoId] = useState<string>("");
  if (isLoading) return <LoadingBlock rows={2} label="Loading repositories" />;
  if (error) return <ErrorState error={error} onRetry={() => refetch()} />;
  const repos = data ?? [];
  if (repos.length === 0) return <p className="text-sm text-muted">No repositories are visible to your GitHub account yet.</p>;
  const repo = repos.find((r) => r.id === repoId);
  return (
    <div className="space-y-5">
      <Field label="Repository" htmlFor="quick-repo">
        <select id="quick-repo" className={inputClass} value={repoId} onChange={(e) => setRepoId(e.target.value)}>
          <option value="">Choose a repository</option>
          {repos.map((r) => (
            <option key={r.id} value={r.id}>
              {r.fullName}
            </option>
          ))}
        </select>
      </Field>
      {repo && <StartReviewForm key={repo.id} repository={repo} />}
    </div>
  );
}
