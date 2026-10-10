import type { AiStatus, FindingCategory, ReviewStatus, Severity } from "../api/types";

export function relativeTime(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return "never";
  const then = new Date(iso).getTime();
  const seconds = Math.round((now.getTime() - then) / 1000);
  if (seconds < 45) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days} d ago`;
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

export const shortSha = (sha: string | null | undefined) => (sha ? sha.slice(0, 7) : "—");

const CATEGORY_LABELS: Record<FindingCategory, string> = {
  BUG: "Bug",
  SECURITY: "Security",
  PERFORMANCE: "Performance",
  RELIABILITY: "Reliability",
  MAINTAINABILITY: "Maintainability",
  CODE_QUALITY: "Code quality",
  TESTING: "Testing",
  BEST_PRACTICE: "Best practice",
};
export const categoryLabel = (c: FindingCategory) => CATEGORY_LABELS[c] ?? c;

export const severityLabel = (s: Severity) => s.charAt(0) + s.slice(1).toLowerCase();

const STATUS_LABELS: Record<ReviewStatus, string> = {
  QUEUED: "Queued",
  RUNNING: "Running",
  COMPLETED: "Completed",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
};
export const statusLabel = (s: ReviewStatus) => STATUS_LABELS[s] ?? s;

export function aiStatusText(s: AiStatus | null): string | null {
  switch (s) {
    case "COMPLETED":
      return "AI review completed";
    case "PARTIAL":
      return "AI review partially completed";
    case "SKIPPED_NOT_CONFIGURED":
      return "AI review skipped: no API key configured";
    case "SKIPPED_DISABLED":
      return "AI review disabled in settings";
    case "FAILED":
      return "AI review failed";
    default:
      return null;
  }
}

export function newIdempotencyKey(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `k${Date.now()}${Math.random().toString(36).slice(2, 10)}`;
}
