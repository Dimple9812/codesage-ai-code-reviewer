import { FolderGit2, Github, Sparkles } from "lucide-react";
import { Link } from "react-router";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

import { useActivity, useDashboardSummary, useDistribution, useMe } from "../api/hooks";
import type { ReviewSummary } from "../api/types";
import { ScoreDial, SeverityBar, StatusPill } from "../components/badges";
import { QuickStartReview } from "../components/StartReview";
import { EmptyState, ErrorState, LoadingBlock, PageHeader, Panel, Skeleton } from "../components/ui";
import { categoryLabel, relativeTime } from "../lib/format";

function Stat({ label, value, tone, detail }: { label: string; value: number | string; tone?: string; detail?: string }) {
  return (
    <div className="px-5 py-4">
      <dt className="text-sm text-muted">{label}</dt>
      <dd className={`mt-1 text-3xl font-bold tabular-nums tracking-tight ${tone ?? ""}`}>{value}</dd>
      {detail && <p className="mt-0.5 text-xs text-faint">{detail}</p>}
    </div>
  );
}

export function ReviewRow({ review }: { review: ReviewSummary }) {
  const target = review.type === "PULL_REQUEST" ? `PR #${review.pullRequestNumber}` : review.branch ?? "default branch";
  return (
    <li>
      <Link to={`/reviews/${review.id}`} className="flex items-center gap-4 px-5 py-3 hover:bg-raised/60">
        <ScoreDial score={review.score} size={40} />
        <div className="min-w-0 flex-1">
          <p className="truncate font-medium">{review.repositoryFullName ?? "Repository"}</p>
          <p className="truncate text-xs text-muted">
            {target} · {relativeTime(review.createdAt)}
            {review.triggerSource === "WEBHOOK" ? " · automatic" : ""}
          </p>
        </div>
        <div className="hidden sm:block">
          <SeverityBar counts={review.counts} />
        </div>
        <StatusPill status={review.status} />
      </Link>
    </li>
  );
}

const tooltipStyle = {
  background: "var(--color-raised)",
  border: "1px solid var(--color-line)",
  borderRadius: 6,
  color: "var(--color-ink)",
  fontSize: 12,
};

export default function Dashboard() {
  const { data: me } = useMe();
  const summary = useDashboardSummary();
  const activity = useActivity(30);
  const distribution = useDistribution();
  const s = summary.data;
  const isNew = s && s.totalReviews === 0;

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title={`Welcome back${me ? `, ${me.name?.split(" ")[0] ?? me.login}` : ""}`}
        description={
          me && (
            <span className="inline-flex items-center gap-1.5">
              <Github className="size-4" aria-hidden /> Connected as @{me.login}
              {me.githubConnected ? "" : " (authorization expired — sign in again)"}
            </span>
          )
        }
      />

      {summary.error ? (
        <ErrorState error={summary.error} onRetry={() => summary.refetch()} />
      ) : (
        <Panel>
          <dl className="grid grid-cols-2 divide-line md:grid-cols-4 md:divide-x">
            {s ? (
              <>
                <Stat label="Repositories" value={s.repositoriesAccessible} detail={`${s.repositoriesReviewed} reviewed`} />
                <Stat label="Reviews run" value={s.totalReviews} detail={s.activeReviews ? `${s.activeReviews} in progress` : `${s.completedReviews} completed`} />
                <Stat
                  label="Open critical / high"
                  value={`${s.openCritical} / ${s.openHigh}`}
                  tone={s.openCritical > 0 ? "text-sev-critical" : s.openHigh > 0 ? "text-sev-high" : "text-sage"}
                />
                <Stat
                  label="Resolved findings"
                  value={s.resolvedFindings}
                  detail={`${s.openFindings} open · ${s.dismissedFindings} dismissed`}
                  tone="text-sage"
                />
              </>
            ) : (
              Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="space-y-2 px-5 py-4">
                  <Skeleton className="h-3 w-24" />
                  <Skeleton className="h-8 w-16" />
                </div>
              ))
            )}
          </dl>
        </Panel>
      )}

      {isNew && (
        <Panel className="mt-6">
          <EmptyState icon={<Sparkles className="size-6" />} title="Run your first review">
            Pick a repository and a branch or pull request. Results usually arrive within a minute, depending on repository size.
          </EmptyState>
          <div className="px-6 pb-6">
            <QuickStartReview />
          </div>
        </Panel>
      )}

      <div className="mt-6 grid gap-6 lg:grid-cols-[1.4fr_1fr]">
        <Panel>
          <div className="flex items-center justify-between border-b border-line px-5 py-3">
            <h2 className="font-semibold">Recent reviews</h2>
            <Link to="/reviews" className="text-sm text-sage hover:underline">
              All reviews
            </Link>
          </div>
          {summary.isLoading ? (
            <LoadingBlock rows={4} label="Loading recent reviews" />
          ) : s && s.recentReviews.length > 0 ? (
            <ul className="divide-y divide-line">
              {s.recentReviews.map((r) => (
                <ReviewRow key={r.id} review={r} />
              ))}
            </ul>
          ) : (
            <EmptyState icon={<FolderGit2 className="size-6" />} title="No reviews yet">
              Reviews you start, and automatic pull request reviews, appear here.
            </EmptyState>
          )}
        </Panel>

        {!isNew && (
          <Panel>
            <h2 className="border-b border-line px-5 py-3 font-semibold">Start a review</h2>
            <div className="p-5">
              <QuickStartReview />
            </div>
          </Panel>
        )}
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Panel>
          <h2 className="border-b border-line px-5 py-3 font-semibold">Review activity, last 30 days</h2>
          <div className="h-60 p-4">
            {activity.isLoading ? (
              <Skeleton className="h-full w-full" />
            ) : activity.error ? (
              <ErrorState error={activity.error} />
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={activity.data} margin={{ left: -20, right: 8, top: 8 }}>
                  <CartesianGrid stroke="var(--color-line)" vertical={false} />
                  <XAxis dataKey="date" tickFormatter={(d: string) => d.slice(5)} stroke="var(--color-faint)" fontSize={11} interval={6} tickLine={false} />
                  <YAxis allowDecimals={false} stroke="var(--color-faint)" fontSize={11} tickLine={false} axisLine={false} />
                  <Tooltip contentStyle={tooltipStyle} cursor={{ fill: "var(--color-raised)" }} />
                  <Bar dataKey="reviews" name="Reviews" fill="var(--color-sage)" radius={[3, 3, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </Panel>

        <Panel>
          <h2 className="border-b border-line px-5 py-3 font-semibold">Most frequent issue categories</h2>
          <div className="p-5">
            {distribution.isLoading ? (
              <LoadingBlock rows={4} />
            ) : distribution.error ? (
              <ErrorState error={distribution.error} />
            ) : (distribution.data ?? []).length === 0 ? (
              <p className="text-sm text-muted">Categories appear after your first completed review.</p>
            ) : (
              <ul className="space-y-3">
                {(() => {
                  const rows = distribution.data ?? [];
                  const max = Math.max(...rows.map((r) => r.count));
                  return rows.map((r) => (
                    <li key={r.category} className="grid grid-cols-[8rem_1fr_2.5rem] items-center gap-3 text-sm">
                      <span className="text-muted">{categoryLabel(r.category)}</span>
                      <div className="h-2 overflow-hidden rounded-full bg-raised">
                        <div className="h-full rounded-full bg-sage" style={{ width: `${(r.count / max) * 100}%` }} />
                      </div>
                      <span className="text-right tabular-nums">{r.count}</span>
                    </li>
                  ));
                })()}
              </ul>
            )}
          </div>
        </Panel>
      </div>
    </div>
  );
}
