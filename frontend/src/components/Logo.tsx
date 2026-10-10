export function Logo({ className = "" }: { className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2 font-bold tracking-tight ${className}`}>
      <svg viewBox="0 0 32 32" className="size-7" aria-hidden>
        <rect width="32" height="32" rx="8" fill="var(--color-raised)" />
        <path d="M11 10 5 16l6 6M21 10l6 6-6 6" fill="none" stroke="var(--color-sage)" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="16" cy="16" r="2.4" fill="var(--color-pollen)" />
      </svg>
      <span>
        CodeSage <span className="font-medium text-muted">AI</span>
      </span>
    </span>
  );
}
