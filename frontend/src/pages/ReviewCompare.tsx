import clsx from "clsx";
import { Link, useParams, useSearchParams } from "react-router";

import { useComparison, useReview, useReviews } from "../api/hooks";
import type { Finding } from "../api/types";
import { CategoryTag, SeverityBadge } from "../components/badges";
import { ErrorState, inputClass, LoadingBlock, PageHeader, Panel } from "../components/ui";
import { dateTime, shortSha } from "../lib/format";

function Column({ title, tone, findings, empty, reviewId }: { title: string; tone: string; findings: Finding[]; empty: string; reviewId: string }) {
  return (
    <Panel>
      <h2 className="flex items-center justify-between border-b border-line px-4 py-3 text-sm font-semibold">
        <span className={tone}>{title}</span>
        <span className="tabular-nums text-muted">{findings.length}</span>
      </h2>
      {findings.length === 0 ? (
        <p className="p-4 text-sm text-muted">{empty}</p>
      ) : (
        <ul className="divide-y divide-line">
          {findings.map((f) => (
            <li key={f.id} className="space-y-1 px-4 py-3">
              <div className="flex flex-wrap gap-1.5">
                <SeverityBadge severity={f.severity} />
                <CategoryTag category={f.category} />
              </div>
              <Link to={`/reviews/${reviewId === f.reviewId ? reviewId : f.reviewId}`} className="block text-sm hover:text-sage">
                {f.title}
              </Link>
              <p className="truncate font-mono text-[11px] text-muted">
                {f.filePath}
                {f.startLine ? `:${f.startLine}` : ""}
              </p>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

export default function ReviewCompare() {
  const { reviewId = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const base = params.get("base") ?? undefined;
  const review = useReview(reviewId);
  const comparison = useComparison(reviewId, base);
  const candidates = useReviews({ repositoryId: review.data?.review.repositoryId, status: "COMPLETED", size: 50 });

  if (review.isLoading) return <LoadingBlock rows={6} />;
  if (review.error || !review.data) return <ErrorState error={review.error} />;
  const r = review.data.review;
  const options = (candidates.data?.content ?? []).filter((c) => c.id !== reviewId && c.createdAt < r.createdAt);

  return (
    <div className="mx-auto max-w-6xl">
      <nav className="mb-3 text-sm text-muted">
        <Link to={`/reviews/${reviewId}`} className="hover:text-ink">
          Back to review
        </Link>
      </nav>
      <PageHeader
        title="Compare reviews"
        description={`${r.repositoryFullName} · this run at ${shortSha(r.commitSha)} (${dateTime(r.createdAt)}) against an earlier completed review. Findings are matched by file, category and the content of the flagged line, so moved code still matches.`}
        actions={
          options.length > 0 && (
            <>
              <label htmlFor="base" className="text-sm text-muted">
                Compare against
              </label>
              <select id="base" className={`${inputClass} w-auto`} value={base ?? comparison.data?.baseReviewId ?? ""} onChange={(e) => setParams({ base: e.target.value })}>
                {options.map((o) => (
                  <option key={o.id} value={o.id}>
                    {dateTime(o.createdAt)} · {o.type === "PULL_REQUEST" ? `PR #${o.pullRequestNumber}` : o.branch} · {shortSha(o.commitSha)}
                  </option>
                ))}
              </select>
            </>
          )
        }
      />
      {comparison.isLoading ? (
        <LoadingBlock rows={6} label="Comparing" />
      ) : comparison.error ? (
        <ErrorState error={comparison.error} />
      ) : (
        <div className={clsx("grid gap-4 lg:grid-cols-3")}>
          <Column title="New" tone="text-sev-high" findings={comparison.data!.newFindings} empty="No new findings since the earlier review." reviewId={reviewId} />
          <Column title="Fixed" tone="text-sage" findings={comparison.data!.fixedFindings} empty="Nothing from the earlier review has disappeared." reviewId={reviewId} />
          <Column title="Recurring" tone="text-muted" findings={comparison.data!.recurringFindings} empty="No findings carried over." reviewId={reviewId} />
        </div>
      )}
    </div>
  );
}
