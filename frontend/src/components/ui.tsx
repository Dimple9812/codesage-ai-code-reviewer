import clsx from "clsx";
import { AlertTriangle, Loader2, RefreshCw } from "lucide-react";
import type { ButtonHTMLAttributes, ReactNode } from "react";

import { ApiError } from "../api/client";

type Variant = "primary" | "secondary" | "ghost" | "danger";

const VARIANTS: Record<Variant, string> = {
  primary: "bg-sage text-night hover:bg-sage-strong font-semibold",
  secondary: "bg-raised text-ink border border-line hover:border-line-strong",
  ghost: "text-muted hover:text-ink hover:bg-raised",
  danger: "bg-sev-critical/15 text-sev-critical border border-sev-critical/40 hover:bg-sev-critical/25",
};

export function Button({
  variant = "secondary",
  loading = false,
  className,
  children,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; loading?: boolean }) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={clsx(
        "inline-flex items-center justify-center gap-2 rounded-md px-3.5 py-2 text-sm transition-colors",
        "disabled:cursor-not-allowed disabled:opacity-50",
        VARIANTS[variant],
        className,
      )}
    >
      {loading && <Loader2 className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  );
}

export function Panel({ className, children }: { className?: string; children: ReactNode }) {
  return <section className={clsx("rounded-lg border border-line bg-surface", className)}>{children}</section>;
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={clsx("skeleton rounded", className)} aria-hidden />;
}

export function LoadingBlock({ rows = 3, label = "Loading" }: { rows?: number; label?: string }) {
  return (
    <div role="status" aria-label={label} className="space-y-3 p-4">
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className={clsx("h-4", i % 2 ? "w-2/3" : "w-full")} />
      ))}
    </div>
  );
}

export function EmptyState({ icon, title, children, action }: { icon?: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-start gap-3 px-6 py-10">
      {icon && <div className="text-sage">{icon}</div>}
      <h3 className="text-lg font-semibold">{title}</h3>
      {children && <div className="max-w-prose text-sm text-muted">{children}</div>}
      {action}
    </div>
  );
}

/** Explains what went wrong and what to do next, keyed on the backend's stable error codes. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const e = error instanceof ApiError ? error : null;
  const hint = e ? errorHint(e) : null;
  return (
    <div role="alert" className="flex items-start gap-3 rounded-lg border border-sev-critical/40 bg-sev-critical/10 p-4">
      <AlertTriangle className="mt-0.5 size-5 shrink-0 text-sev-critical" aria-hidden />
      <div className="space-y-1 text-sm">
        <p className="font-medium">{e?.message ?? (error instanceof Error ? error.message : "Something went wrong.")}</p>
        {hint && <p className="text-muted">{hint}</p>}
        {e?.correlationId && <p className="text-faint">Reference: <span className="font-mono">{e.correlationId}</span></p>}
        {onRetry && (
          <button onClick={onRetry} className="mt-1 inline-flex items-center gap-1.5 text-sage hover:underline">
            <RefreshCw className="size-3.5" aria-hidden /> Try again
          </button>
        )}
      </div>
    </div>
  );
}

export function errorHint(e: ApiError): string | null {
  switch (e.code) {
    case "GITHUB_REAUTH_REQUIRED":
      return "Sign in with GitHub again to renew access.";
    case "GITHUB_RATE_LIMITED":
      return "GitHub limits API calls per hour. Wait a few minutes before retrying.";
    case "GITHUB_UNAVAILABLE":
      return "GitHub did not respond. Check githubstatus.com and retry.";
    case "GITHUB_NOT_FOUND":
      return "The repository may have been deleted, renamed, or your access was removed.";
    case "NETWORK_ERROR":
      return "If you are running locally, start the backend with docker compose up.";
    case "RATE_LIMITED":
      return "You have reached the request limit. It resets within the hour.";
    default:
      return null;
  }
}

export function PageHeader({ title, description, actions }: { title: string; description?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{title}</h1>
        {description && <p className="mt-1 max-w-prose text-sm text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

export function Field({ label, htmlFor, children, hint }: { label: string; htmlFor: string; children: ReactNode; hint?: ReactNode }) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="block text-sm font-medium">
        {label}
      </label>
      {children}
      {hint && <p className="text-xs text-muted">{hint}</p>}
    </div>
  );
}

export const inputClass =
  "w-full rounded-md border border-line bg-night px-3 py-2 text-sm text-ink placeholder:text-faint focus:border-sage focus:outline-none";
