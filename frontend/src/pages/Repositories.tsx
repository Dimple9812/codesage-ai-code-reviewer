import { FolderGit2, Lock, RefreshCw, Search } from "lucide-react";
import { useDeferredValue, useMemo, useState } from "react";
import { Link } from "react-router";

import { useRefreshRepositories, useRepositories } from "../api/hooks";
import { Button, EmptyState, ErrorState, inputClass, LoadingBlock, PageHeader, Panel } from "../components/ui";
import { relativeTime } from "../lib/format";

export default function Repositories() {
  const [query, setQuery] = useState("");
  const [language, setLanguage] = useState("");
  const [visibility, setVisibility] = useState<"all" | "public" | "private">("all");
  const deferredQuery = useDeferredValue(query);
  const { data, isLoading, error, refetch, isFetching } = useRepositories(deferredQuery, language);
  const refresh = useRefreshRepositories();
  const all = useRepositories();

  const languages = useMemo(
    () => Array.from(new Set((all.data ?? []).map((r) => r.language).filter((l): l is string => !!l))).sort(),
    [all.data],
  );
  const repos = (data ?? []).filter((r) => visibility === "all" || (visibility === "private") === r.isPrivate);

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title="Repositories"
        description="Repositories your GitHub account can access. Select one to browse branches, commits and pull requests, or to start a review."
        actions={
          <Button onClick={() => refresh.mutate()} loading={refresh.isPending}>
            <RefreshCw className="size-4" aria-hidden /> Sync from GitHub
          </Button>
        }
      />

      <div className="mb-4 flex flex-wrap gap-3">
        <div className="relative min-w-[16rem] flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" aria-hidden />
          <label htmlFor="repo-search" className="sr-only">
            Search repositories
          </label>
          <input
            id="repo-search"
            className={`${inputClass} pl-9`}
            placeholder="Search by name or description"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <label className="sr-only" htmlFor="repo-language">
          Language
        </label>
        <select id="repo-language" className={`${inputClass} w-auto`} value={language} onChange={(e) => setLanguage(e.target.value)}>
          <option value="">All languages</option>
          {languages.map((l) => (
            <option key={l}>{l}</option>
          ))}
        </select>
        <label className="sr-only" htmlFor="repo-visibility">
          Visibility
        </label>
        <select
          id="repo-visibility"
          className={`${inputClass} w-auto`}
          value={visibility}
          onChange={(e) => setVisibility(e.target.value as typeof visibility)}
        >
          <option value="all">Public and private</option>
          <option value="public">Public only</option>
          <option value="private">Private only</option>
        </select>
      </div>

      <Panel>
        {isLoading ? (
          <LoadingBlock rows={6} label="Loading repositories" />
        ) : error ? (
          <div className="p-5">
            <ErrorState error={error} onRetry={() => refetch()} />
          </div>
        ) : repos.length === 0 ? (
          <EmptyState icon={<FolderGit2 className="size-6" />} title={query || language ? "No repositories match" : "No repositories found"}>
            {query || language
              ? "Try a different search or language."
              : "Your GitHub account has no repositories CodeSage can see. If you expected private repositories, sign in again and grant the repo scope."}
          </EmptyState>
        ) : (
          <ul className={`divide-y divide-line ${isFetching ? "opacity-70" : ""}`}>
            {repos.map((r) => (
              <li key={r.id}>
                <Link to={`/repositories/${r.id}`} className="grid gap-1 px-5 py-4 hover:bg-raised/60 sm:grid-cols-[1fr_auto] sm:items-center">
                  <div className="min-w-0">
                    <p className="flex items-center gap-2 font-medium">
                      <span className="truncate">{r.fullName}</span>
                      {r.isPrivate && (
                        <span className="inline-flex items-center gap-1 rounded bg-raised px-1.5 py-0.5 text-xs text-muted">
                          <Lock className="size-3" aria-hidden /> Private
                        </span>
                      )}
                      {r.autoReview && <span className="rounded bg-sage-deep/60 px-1.5 py-0.5 text-xs text-sage-strong">Auto-review</span>}
                    </p>
                    {r.description && <p className="mt-0.5 truncate text-sm text-muted">{r.description}</p>}
                  </div>
                  <div className="flex gap-4 text-xs text-muted">
                    {r.language && <span>{r.language}</span>}
                    {r.defaultBranch && <span className="font-mono">{r.defaultBranch}</span>}
                    <span>Pushed {relativeTime(r.pushedAt)}</span>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </div>
  );
}
