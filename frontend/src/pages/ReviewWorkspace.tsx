import clsx from "clsx";
import { ChevronDown, ChevronRight, ExternalLink, FileCode2, FileX2, GitCompare, Info, Send, XCircle } from "lucide-react";
import { lazy, Suspense, useMemo, useState } from "react";
import { Link, useParams } from "react-router";

import {
  useCancelReview,
  useFileContent,
  useFindings,
  usePublishComments,
  useReview,
  useReviewFiles,
  useReviewStatus,
  useUpdateFinding,
} from "../api/hooks";
import { CATEGORIES, SEVERITIES, type Finding, type FindingStatus, type ReviewDetail, type ReviewFile } from "../api/types";
import { CategoryTag, ScoreDial, SEV_TEXT, SeverityBadge, SourceBadge, StatusPill } from "../components/badges";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { SafeMarkdown } from "../components/SafeMarkdown";
import { Button, EmptyState, ErrorState, inputClass, LoadingBlock, Panel } from "../components/ui";
import { aiStatusText, categoryLabel, dateTime, severityLabel, shortSha } from "../lib/format";

const CodeViewer = lazy(() => import("../components/CodeViewer"));

export function parseRanges(value: string | null | undefined): Set<number> {
  const out = new Set<number>();
  if (!value) return out;
  for (const part of value.split(",")) {
    const [a, b] = part.split("-").map(Number);
    if (!Number.isFinite(a)) continue;
    for (let n = a; n <= (Number.isFinite(b) ? b : a); n++) out.add(n);
  }
  return out;
}

interface Filters {
  severity: string;
  category: string;
  source: string;
  status: string;
}

export function filterFindings(findings: Finding[], f: Filters, file: string | null): Finding[] {
  return findings.filter(
    (x) =>
      (!f.severity || x.severity === f.severity) &&
      (!f.category || x.category === f.category) &&
      (!f.source || x.source === f.source) &&
      (!f.status || x.status === f.status) &&
      (!file || x.filePath === file),
  );
}

export default function ReviewWorkspace() {
  const { reviewId = "" } = useParams();
  const status = useReviewStatus(reviewId);
  const detail = useReview(reviewId);
  const done = status.data?.status === "COMPLETED";
  const findings = useFindings(reviewId, done);
  const files = useReviewFiles(reviewId, done);
  const cancel = useCancelReview(reviewId);

  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [focusLine, setFocusLine] = useState<number | null>(null);
  const [selectedFinding, setSelectedFinding] = useState<string | null>(null);
  const [fileFilterOnly, setFileFilterOnly] = useState(false);
  const [filters, setFilters] = useState<Filters>({ severity: "", category: "", source: "", status: "" });

  if (detail.isLoading || status.isLoading) return <LoadingBlock rows={8} label="Loading review" />;
  if (detail.error || !detail.data) return <ErrorState error={detail.error ?? status.error} onRetry={() => detail.refetch()} />;

  const d = detail.data;
  const current = status.data?.status ?? d.review.status;
  const all = findings.data ?? [];
  const firstFileWithFindings = all.find((f) => f.startLine)?.filePath ?? files.data?.find((f) => f.analyzed)?.path ?? null;
  const activeFile = selectedFile ?? firstFileWithFindings;
  const visible = filterFindings(all, filters, fileFilterOnly ? activeFile : null);

  function openFinding(f: Finding) {
    setSelectedFinding(f.id === selectedFinding ? null : f.id);
    setSelectedFile(f.filePath);
    setFocusLine(f.startLine);
  }

  return (
    <div className="mx-auto max-w-[110rem]">
      <WorkspaceHeader detail={d} current={current} onCancel={() => cancel.mutate()} cancelling={cancel.isPending} />

      {current === "QUEUED" || current === "RUNNING" ? (
        <Panel className="p-8">
          <div className="flex items-start gap-4">
            <StatusPill status={current} />
            <div className="space-y-2 text-sm text-muted">
              <p>
                {current === "QUEUED"
                  ? "Waiting for a worker. Reviews run in the background; you can leave this page."
                  : "Fetching files from GitHub, running static analyzers, then the AI review. This page updates automatically."}
              </p>
              {d.errorMessage && <p className="text-pollen">{d.errorMessage}</p>}
            </div>
          </div>
          <div className="mt-6 space-y-2">
            <div className="skeleton h-2 w-full rounded-full" />
          </div>
        </Panel>
      ) : current === "FAILED" ? (
        <ErrorState error={new Error(d.errorMessage ?? status.data?.errorMessage ?? "The review failed.")} />
      ) : current === "CANCELLED" ? (
        <Panel>
          <EmptyState icon={<XCircle className="size-6" />} title="This review was cancelled">
            Start a new review from the repository page when you are ready.
          </EmptyState>
        </Panel>
      ) : (
        <>
          <SummaryPanel detail={d} />
          <div className="mt-4 grid grid-cols-[minmax(0,1fr)] gap-4 xl:grid-cols-[minmax(0,1fr)_25rem] 2xl:grid-cols-[16rem_minmax(0,1fr)_26rem]">
            <FileExplorer
              files={files.data}
              loading={files.isLoading}
              active={activeFile}
              onSelect={(p) => {
                setSelectedFile(p);
                setFocusLine(null);
              }}
            />
            <SourcePane reviewId={reviewId} path={activeFile} files={files.data} findings={all} focusLine={focusLine} onSelectFile={(p) => {
                setSelectedFile(p);
                setFocusLine(null);
              }} onSelectLine={(l) => {
              const hit = all.find((f) => f.filePath === activeFile && f.startLine && l >= f.startLine && l <= (f.endLine ?? f.startLine));
              if (hit) setSelectedFinding(hit.id);
            }} />
            <FindingsPanel
              detail={d}
              findings={visible}
              total={all.length}
              loading={findings.isLoading}
              error={findings.error}
              filters={filters}
              setFilters={setFilters}
              fileFilter={fileFilterOnly ? activeFile : null}
              toggleFileFilter={() => setFileFilterOnly((v) => !v)}
              selected={selectedFinding}
              onOpen={openFinding}
            />
          </div>
        </>
      )}
    </div>
  );
}

function WorkspaceHeader({ detail, current, onCancel, cancelling }: { detail: ReviewDetail; current: string; onCancel: () => void; cancelling: boolean }) {
  const r = detail.review;
  const target = r.type === "PULL_REQUEST" ? `Pull request #${r.pullRequestNumber}` : `Branch ${r.branch ?? ""}`;
  return (
    <header className="mb-4 flex flex-wrap items-center gap-x-6 gap-y-3">
      <ScoreDial score={r.score} size={60} />
      <div className="min-w-0 flex-1">
        <nav className="text-sm text-muted">
          <Link to={`/repositories/${r.repositoryId}`} className="hover:text-ink">
            {r.repositoryFullName}
          </Link>
        </nav>
        <h1 className="truncate text-2xl font-bold tracking-tight">
          {r.type === "PULL_REQUEST" && r.pullRequestTitle ? r.pullRequestTitle : target}
        </h1>
        <p className="mt-0.5 flex flex-wrap gap-x-3 text-sm text-muted">
          <span>{target}</span>
          <span className="font-mono">{shortSha(r.commitSha)}</span>
          <span>Started {dateTime(r.createdAt)}</span>
          {r.triggerSource === "WEBHOOK" && <span>Triggered by webhook</span>}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <StatusPill status={r.status === current ? r.status : (current as typeof r.status)} />
        {(current === "QUEUED" || current === "RUNNING") && (
          <Button variant="danger" onClick={onCancel} loading={cancelling}>
            Cancel review
          </Button>
        )}
        {current === "COMPLETED" && (
          <Link to={`/reviews/${r.id}/compare`} className="inline-flex items-center gap-2 rounded-md border border-line px-3.5 py-2 text-sm hover:border-line-strong">
            <GitCompare className="size-4" aria-hidden /> Compare with previous
          </Link>
        )}
        {detail.pullRequestHtmlUrl && (
          <a href={detail.pullRequestHtmlUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 px-2 text-sm text-muted hover:text-ink">
            GitHub <ExternalLink className="size-3.5" aria-hidden />
          </a>
        )}
      </div>
    </header>
  );
}

function SummaryPanel({ detail }: { detail: ReviewDetail }) {
  const [showNotes, setShowNotes] = useState(false);
  const r = detail.review;
  const ai = aiStatusText(r.aiStatus);
  return (
    <Panel className="p-5">
      <div className="grid gap-6 lg:grid-cols-[1fr_auto]">
        <div className="space-y-3">
          <SafeMarkdown>{detail.summary}</SafeMarkdown>
          <p className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
            <span>
              {detail.filesAnalyzed} of {detail.filesConsidered} supported files analysed
            </span>
            {ai && (
              <span className={clsx(r.aiStatus === "COMPLETED" ? "text-muted" : "text-pollen")}>
                {ai}
                {detail.aiModel ? ` (${detail.aiProvider} · ${detail.aiModel})` : ""}
              </span>
            )}
            <span title={detail.scoreFormula} className="inline-flex cursor-help items-center gap-1">
              <Info className="size-3.5" aria-hidden /> How the score works
            </span>
          </p>
          {detail.notes.length > 0 && (
            <div>
              <button onClick={() => setShowNotes((s) => !s)} className="inline-flex items-center gap-1 text-xs text-muted hover:text-ink" aria-expanded={showNotes}>
                {showNotes ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />} {detail.notes.length} note
                {detail.notes.length === 1 ? "" : "s"} about this run
              </button>
              {showNotes && (
                <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-muted">
                  {detail.notes.map((n) => (
                    <li key={n}>{n}</li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>
        <dl className="grid grid-cols-5 gap-4 text-center">
          {SEVERITIES.map((s) => (
            <div key={s}>
              <dt className="text-xs text-muted">{severityLabel(s)}</dt>
              <dd className={clsx("text-2xl font-bold tabular-nums", SEV_TEXT[s])}>{r.counts[s.toLowerCase() as keyof typeof r.counts]}</dd>
            </div>
          ))}
        </dl>
      </div>
    </Panel>
  );
}

function FileExplorer({ files, loading, active, onSelect }: { files?: ReviewFile[]; loading: boolean; active: string | null; onSelect: (p: string) => void }) {
  const [showSkipped, setShowSkipped] = useState(false);
  const analyzed = (files ?? []).filter((f) => f.analyzed);
  const skipped = (files ?? []).filter((f) => !f.analyzed);
  return (
    <Panel className="hidden max-h-[44rem] flex-col 2xl:flex">
      <h2 className="border-b border-line px-4 py-3 text-sm font-semibold">Files ({analyzed.length})</h2>
      {loading ? (
        <LoadingBlock rows={6} label="Loading files" />
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto py-1">
          <ul>
            {analyzed.map((f) => (
              <li key={f.path}>
                <button
                  onClick={() => onSelect(f.path)}
                  className={clsx(
                    "flex w-full items-center gap-2 px-4 py-1.5 text-left text-xs",
                    active === f.path ? "bg-raised text-ink" : "text-muted hover:bg-raised/50 hover:text-ink",
                  )}
                  title={f.path}
                  aria-current={active === f.path ? "true" : undefined}
                >
                  <FileCode2 className="size-3.5 shrink-0" aria-hidden />
                  <span className="min-w-0 flex-1 truncate font-mono" dir="rtl">
                    {f.path}
                  </span>
                  {f.findingCount > 0 && <span className="rounded bg-sev-high/15 px-1.5 text-[11px] font-semibold text-sev-high">{f.findingCount}</span>}
                </button>
              </li>
            ))}
          </ul>
          {skipped.length > 0 && (
            <div className="mt-2 border-t border-line pt-2">
              <button onClick={() => setShowSkipped((s) => !s)} className="flex items-center gap-1 px-4 py-1 text-xs text-faint hover:text-muted" aria-expanded={showSkipped}>
                {showSkipped ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />} {skipped.length} skipped
              </button>
              {showSkipped && (
                <ul>
                  {skipped.map((f) => (
                    <li key={f.path} className="flex items-start gap-2 px-4 py-1 text-xs text-faint" title={f.skipReason ?? undefined}>
                      <FileX2 className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                      <span className="min-w-0">
                        <span className="block truncate font-mono">{f.path}</span>
                        <span>{f.skipReason}</span>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>
      )}
    </Panel>
  );
}

function SourcePane({
  reviewId,
  path,
  files,
  findings,
  focusLine,
  onSelectFile,
  onSelectLine,
}: {
  reviewId: string;
  path: string | null;
  files?: ReviewFile[];
  findings: Finding[];
  focusLine: number | null;
  onSelectFile: (path: string) => void;
  onSelectLine: (line: number) => void;
}) {
  const content = useFileContent(reviewId, path);
  const fileFindings = useMemo(() => findings.filter((f) => f.filePath === path), [findings, path]);
  const changed = useMemo(() => parseRanges(files?.find((f) => f.path === path)?.changedLines), [files, path]);
  return (
    <Panel className="flex h-[44rem] min-w-0 flex-col overflow-hidden">
      <div className="flex items-center justify-between gap-3 border-b border-line px-4 py-3 text-sm">
        <span className="hidden truncate font-mono text-xs text-muted 2xl:inline">{path ?? "No file selected"}</span>
        {/* Below 2xl the explorer column is hidden; pick files here instead. */}
        <label htmlFor="file-picker" className="sr-only">
          File
        </label>
        <select
          id="file-picker"
          className={`${inputClass} min-w-0 flex-1 py-1 font-mono text-xs 2xl:hidden`}
          value={path ?? ""}
          onChange={(e) => onSelectFile(e.target.value)}
        >
          {(files ?? [])
            .filter((f) => f.analyzed)
            .map((f) => (
              <option key={f.path} value={f.path}>
                {f.path}
                {f.findingCount ? ` (${f.findingCount})` : ""}
              </option>
            ))}
        </select>
        {changed.size > 0 && <span className="shrink-0 text-xs text-sage">Changed lines marked in the gutter</span>}
      </div>
      <div className="min-h-0 flex-1">
        {!path ? (
          <EmptyState title="Select a file">Choose a file or a finding to view the source at the reviewed commit.</EmptyState>
        ) : content.isLoading ? (
          <LoadingBlock rows={12} label="Loading file from GitHub" />
        ) : content.error ? (
          <div className="p-4">
            <ErrorState error={content.error} onRetry={() => content.refetch()} />
          </div>
        ) : (
          <Suspense fallback={<LoadingBlock rows={12} label="Loading editor" />}>
            <CodeViewer
              path={path}
              language={content.data!.language}
              content={content.data!.content}
              findings={fileFindings}
              focusLine={focusLine}
              changedLines={changed}
              onSelectLine={onSelectLine}
            />
          </Suspense>
        )}
      </div>
    </Panel>
  );
}

function FindingsPanel({
  detail,
  findings,
  total,
  loading,
  error,
  filters,
  setFilters,
  fileFilter,
  toggleFileFilter,
  selected,
  onOpen,
}: {
  detail: ReviewDetail;
  findings: Finding[];
  total: number;
  loading: boolean;
  error: unknown;
  filters: Filters;
  setFilters: (f: Filters) => void;
  fileFilter: string | null;
  toggleFileFilter: () => void;
  selected: string | null;
  onOpen: (f: Finding) => void;
}) {
  const isPr = detail.review.type === "PULL_REQUEST";
  const [toPublish, setToPublish] = useState<Set<string>>(new Set());
  const [confirming, setConfirming] = useState(false);
  const publish = usePublishComments(detail.review.id);
  const update = useUpdateFinding(detail.review.id);

  const set = (k: keyof Filters) => (e: React.ChangeEvent<HTMLSelectElement>) => setFilters({ ...filters, [k]: e.target.value });

  return (
    <Panel className="flex max-h-[44rem] flex-col">
      <div className="space-y-3 border-b border-line p-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold">
            Findings <span className="text-muted">({findings.length} of {total})</span>
          </h2>
          <label className="flex cursor-pointer items-center gap-1.5 text-xs text-muted">
            <input type="checkbox" checked={!!fileFilter} onChange={toggleFileFilter} className="accent-[var(--color-sage)]" /> Current file only
          </label>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <select aria-label="Filter by severity" className={`${inputClass} py-1.5 text-xs`} value={filters.severity} onChange={set("severity")}>
            <option value="">All severities</option>
            {SEVERITIES.map((s) => (
              <option key={s} value={s}>
                {severityLabel(s)}
              </option>
            ))}
          </select>
          <select aria-label="Filter by category" className={`${inputClass} py-1.5 text-xs`} value={filters.category} onChange={set("category")}>
            <option value="">All categories</option>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {categoryLabel(c)}
              </option>
            ))}
          </select>
          <select aria-label="Filter by source" className={`${inputClass} py-1.5 text-xs`} value={filters.source} onChange={set("source")}>
            <option value="">AI and static</option>
            <option value="AI">AI only</option>
            <option value="STATIC_ANALYSIS">Static analysis only</option>
          </select>
          <select aria-label="Filter by status" className={`${inputClass} py-1.5 text-xs`} value={filters.status} onChange={set("status")}>
            <option value="">Any status</option>
            <option value="OPEN">Open</option>
            <option value="RESOLVED">Resolved</option>
            <option value="DISMISSED">Dismissed</option>
          </select>
        </div>
        {isPr && (
          <div className="flex items-center justify-between gap-2 rounded-md bg-night px-3 py-2 text-xs text-muted">
            <span>{toPublish.size} selected for GitHub</span>
            <Button variant="primary" className="px-2.5 py-1 text-xs" disabled={toPublish.size === 0} onClick={() => setConfirming(true)}>
              <Send className="size-3.5" aria-hidden /> Publish comments
            </Button>
          </div>
        )}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {loading ? (
          <LoadingBlock rows={6} label="Loading findings" />
        ) : error ? (
          <div className="p-4">
            <ErrorState error={error} />
          </div>
        ) : total === 0 ? (
          <EmptyState title="No findings">Nothing at or above your minimum severity was found in the reviewed files.</EmptyState>
        ) : findings.length === 0 ? (
          <EmptyState title="No findings match these filters" />
        ) : (
          <ul className="divide-y divide-line">
            {findings.map((f) => (
              <FindingItem
                key={f.id}
                finding={f}
                open={selected === f.id}
                onOpen={() => onOpen(f)}
                isPr={isPr}
                checked={toPublish.has(f.id)}
                onCheck={(c) =>
                  setToPublish((prev) => {
                    const next = new Set(prev);
                    if (c) next.add(f.id);
                    else next.delete(f.id);
                    return next;
                  })
                }
                onStatus={(status) => update.mutate({ id: f.id, status })}
              />
            ))}
          </ul>
        )}
      </div>

      <ConfirmDialog
        open={confirming}
        title={`Publish ${toPublish.size} comment${toPublish.size === 1 ? "" : "s"} to GitHub?`}
        confirmLabel="Publish comments"
        loading={publish.isPending}
        onClose={() => setConfirming(false)}
        onConfirm={() =>
          publish.mutate([...toPublish], {
            onSuccess: () => {
              setConfirming(false);
              setToPublish(new Set());
            },
          })
        }
      >
        <p>
          The selected findings will be posted as one review on pull request #{detail.review.pullRequestNumber}, visible to everyone with access
          to the repository. The review is a comment only: nothing is approved, blocked, merged or changed.
        </p>
      </ConfirmDialog>
    </Panel>
  );
}

function FindingItem({
  finding: f,
  open,
  onOpen,
  isPr,
  checked,
  onCheck,
  onStatus,
}: {
  finding: Finding;
  open: boolean;
  onOpen: () => void;
  isPr: boolean;
  checked: boolean;
  onCheck: (c: boolean) => void;
  onStatus: (s: FindingStatus) => void;
}) {
  const location = f.startLine ? `${f.filePath}:${f.startLine}${f.endLine && f.endLine !== f.startLine ? `-${f.endLine}` : ""}` : f.filePath;
  const publishable = isPr && f.inDiff && !f.publishedAt && f.startLine;
  return (
    <li className={clsx(open && "bg-raised/40", f.status !== "OPEN" && "opacity-60")}>
      <div className="flex gap-2 px-4 py-3">
        {isPr && (
          <input
            type="checkbox"
            aria-label={`Select "${f.title}" for publishing`}
            className="mt-1 accent-[var(--color-sage)]"
            disabled={!publishable}
            title={publishable ? "Select to publish" : f.publishedAt ? "Already published" : "Only lines inside the diff can be commented on"}
            checked={checked}
            onChange={(e) => onCheck(e.target.checked)}
          />
        )}
        <button onClick={onOpen} className="min-w-0 flex-1 text-left" aria-expanded={open}>
          <div className="flex flex-wrap items-center gap-1.5">
            <SeverityBadge severity={f.severity} />
            <CategoryTag category={f.category} />
            <SourceBadge source={f.source} analyzer={f.analyzer} verification={f.verification} />
            {f.status !== "OPEN" && <span className="text-xs text-muted">{f.status.toLowerCase()}</span>}
            {f.publishedAt && <span className="text-xs text-sage">on GitHub</span>}
          </div>
          <p className="mt-1.5 text-sm font-medium">{f.title}</p>
          <p className="mt-0.5 truncate font-mono text-[11px] text-muted">{location}</p>
        </button>
      </div>
      {open && (
        <div className="space-y-3 px-4 pb-4">
          <SafeMarkdown>{f.description}</SafeMarkdown>
          {f.impact && (
            <div>
              <h4 className="text-xs font-semibold text-muted">Why it matters</h4>
              <SafeMarkdown>{f.impact}</SafeMarkdown>
            </div>
          )}
          {f.recommendation && (
            <div>
              <h4 className="text-xs font-semibold text-muted">How to fix</h4>
              <SafeMarkdown>{f.recommendation}</SafeMarkdown>
            </div>
          )}
          {(f.originalCode || f.suggestedCode) && (
            <div className="space-y-2">
              {f.originalCode && (
                <div>
                  <h4 className="text-xs font-semibold text-sev-critical/90">Before</h4>
                  <pre className="mt-1 overflow-x-auto rounded border border-sev-critical/25 bg-night p-2 font-mono text-[12px] leading-5">{f.originalCode}</pre>
                </div>
              )}
              {f.suggestedCode && (
                <div>
                  <h4 className="text-xs font-semibold text-sage">Suggested</h4>
                  <pre className="mt-1 overflow-x-auto rounded border border-sage/30 bg-night p-2 font-mono text-[12px] leading-5">{f.suggestedCode}</pre>
                  <p className="mt-1 text-[11px] text-faint">Suggestions are not applied automatically. Review before using.</p>
                </div>
              )}
            </div>
          )}
          <p className="text-[11px] text-faint">
            {f.source === "AI" ? `Model confidence ${Math.round(f.confidence * 100)}%` : `Rule ${f.ruleId ?? f.analyzer}`}
          </p>
          <div className="flex flex-wrap gap-2">
            {f.status !== "RESOLVED" && (
              <Button className="px-2.5 py-1 text-xs" onClick={() => onStatus("RESOLVED")}>
                Mark resolved
              </Button>
            )}
            {f.status !== "DISMISSED" && (
              <Button variant="ghost" className="px-2.5 py-1 text-xs" onClick={() => onStatus("DISMISSED")}>
                Dismiss
              </Button>
            )}
            {f.status !== "OPEN" && (
              <Button variant="ghost" className="px-2.5 py-1 text-xs" onClick={() => onStatus("OPEN")}>
                Reopen
              </Button>
            )}
          </div>
        </div>
      )}
    </li>
  );
}
