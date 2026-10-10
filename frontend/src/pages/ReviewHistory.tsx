import { History } from "lucide-react";
import { Link, useSearchParams } from "react-router";

import { useRepositories, useReviews } from "../api/hooks";
import { SEVERITIES } from "../api/types";
import { ScoreDial, SeverityBar, StatusPill } from "../components/badges";
import { Button, EmptyState, ErrorState, inputClass, LoadingBlock, PageHeader, Panel } from "../components/ui";
import { dateTime, severityLabel, shortSha } from "../lib/format";

const PAGE_SIZE = 20;

export default function ReviewHistory() {
  const [params, setParams] = useSearchParams();
  const get = (k: string) => params.get(k) ?? "";
  const page = Number(params.get("page") ?? 0);
  const fromDate = get("from");
  const toDate = get("to");
  const filters = {
    repositoryId: get("repositoryId") || undefined,
    status: get("status") || undefined,
    severity: get("severity") || undefined,
    branch: get("branch") || undefined,
    from: fromDate ? new Date(`${fromDate}T00:00:00`).toISOString() : undefined,
    to: toDate ? new Date(`${toDate}T23:59:59`).toISOString() : undefined,
    page,
    size: PAGE_SIZE,
  };
  const { data, isLoading, error, refetch, isFetching } = useReviews(filters);
  const repos = useRepositories();

  function update(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.delete("page");
    setParams(next, { replace: true });
  }

  const hasFilters = ["repositoryId", "status", "severity", "branch", "from", "to"].some((k) => params.get(k));

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader title="Review history" description="Every review you have run, newest first. Open one to see its findings or compare it with an earlier run." />

      <Panel className="mb-4 p-4">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
          <select aria-label="Repository" className={`${inputClass} lg:col-span-2`} value={get("repositoryId")} onChange={(e) => update("repositoryId", e.target.value)}>
            <option value="">All repositories</option>
            {(repos.data ?? []).map((r) => (
              <option key={r.id} value={r.id}>
                {r.fullName}
              </option>
            ))}
          </select>
          <select aria-label="Status" className={inputClass} value={get("status")} onChange={(e) => update("status", e.target.value)}>
            <option value="">Any status</option>
            {["QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"].map((s) => (
              <option key={s} value={s}>
                {s.charAt(0) + s.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
          <select aria-label="Minimum severity present" className={inputClass} value={get("severity")} onChange={(e) => update("severity", e.target.value)}>
            <option value="">Any findings</option>
            {SEVERITIES.map((s) => (
              <option key={s} value={s}>
                Has {severityLabel(s).toLowerCase()}+
              </option>
            ))}
          </select>
          <input aria-label="Branch" className={inputClass} placeholder="Branch" defaultValue={get("branch")} onBlur={(e) => update("branch", e.target.value.trim())} onKeyDown={(e) => e.key === "Enter" && update("branch", (e.target as HTMLInputElement).value.trim())} />
          <div className="flex gap-2">
            <input aria-label="From date" type="date" className={inputClass} value={fromDate} onChange={(e) => update("from", e.target.value)} />
            <input aria-label="To date" type="date" className={inputClass} value={toDate} onChange={(e) => update("to", e.target.value)} />
          </div>
        </div>
        {hasFilters && (
          <button className="mt-3 text-xs text-sage hover:underline" onClick={() => setParams(new URLSearchParams(), { replace: true })}>
            Clear filters
          </button>
        )}
      </Panel>

      <Panel>
        {isLoading ? (
          <LoadingBlock rows={8} label="Loading reviews" />
        ) : error ? (
          <div className="p-5">
            <ErrorState error={error} onRetry={() => refetch()} />
          </div>
        ) : (data?.content ?? []).length === 0 ? (
          <EmptyState icon={<History className="size-6" />} title={hasFilters ? "No reviews match these filters" : "No reviews yet"}>
            {hasFilters ? "Widen the date range or clear a filter." : <Link className="text-sage hover:underline" to="/repositories">Choose a repository to review.</Link>}
          </EmptyState>
        ) : (
          <div className={`overflow-x-auto ${isFetching ? "opacity-70" : ""}`}>
            <table className="w-full min-w-[48rem] text-sm">
              <thead className="text-left text-xs text-muted">
                <tr className="border-b border-line">
                  <th className="px-5 py-3 font-medium">Score</th>
                  <th className="px-3 py-3 font-medium">Repository</th>
                  <th className="px-3 py-3 font-medium">Target</th>
                  <th className="px-3 py-3 font-medium">Findings</th>
                  <th className="px-3 py-3 font-medium">Status</th>
                  <th className="px-3 py-3 font-medium">Started</th>
                  <th className="px-5 py-3 font-medium"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data!.content.map((r) => (
                  <tr key={r.id} className="hover:bg-raised/40">
                    <td className="px-5 py-2">
                      <ScoreDial score={r.score} size={36} />
                    </td>
                    <td className="px-3 py-2">
                      <Link to={`/reviews/${r.id}`} className="font-medium hover:text-sage">
                        {r.repositoryFullName}
                      </Link>
                    </td>
                    <td className="px-3 py-2 text-muted">
                      {r.type === "PULL_REQUEST" ? `PR #${r.pullRequestNumber}` : <span className="font-mono text-xs">{r.branch}</span>}
                      <span className="ml-2 font-mono text-xs text-faint">{shortSha(r.commitSha)}</span>
                    </td>
                    <td className="px-3 py-2">
                      <SeverityBar counts={r.counts} />
                    </td>
                    <td className="px-3 py-2">
                      <StatusPill status={r.status} />
                    </td>
                    <td className="px-3 py-2 text-muted">{dateTime(r.createdAt)}</td>
                    <td className="px-5 py-2 text-right">
                      {r.status === "COMPLETED" && (
                        <Link to={`/reviews/${r.id}/compare`} className="text-xs text-sage hover:underline">
                          Compare
                        </Link>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-line px-5 py-3 text-sm text-muted">
            <span>
              Page {data.page + 1} of {data.totalPages} · {data.totalElements} reviews
            </span>
            <div className="flex gap-2">
              <Button disabled={page === 0} onClick={() => update("page", String(page - 1))}>
                Previous
              </Button>
              <Button disabled={page + 1 >= data.totalPages} onClick={() => update("page", String(page + 1))}>
                Next
              </Button>
            </div>
          </div>
        )}
      </Panel>
    </div>
  );
}
