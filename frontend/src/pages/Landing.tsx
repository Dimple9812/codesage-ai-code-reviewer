import { Bug, FileSearch, Gauge, GitPullRequest, Github, ShieldAlert } from "lucide-react";
import { Link } from "react-router";

import { useAuthConfig, useMe } from "../api/hooks";
import { Logo } from "../components/Logo";
import { ScoreDial, SeverityBadge } from "../components/badges";

const REPO_URL = "https://github.com/Dimple9812/codesage-ai-code-reviewer";

const SAMPLE = [
  "public class UserService {",
  "    private final UserRepository users;",
  "",
  "    public String displayName(long id) {",
  "        User user = users.findById(id)",
  "                .orElse(null);",
  "        return user.getName().trim();",
  "    }",
  "}",
];

function HeroExample() {
  return (
    <figure className="overflow-hidden rounded-xl border border-line bg-surface shadow-2xl shadow-black/30">
      <figcaption className="flex items-center justify-between border-b border-line px-4 py-2.5 text-xs text-muted">
        <span className="font-mono">src/main/java/app/UserService.java</span>
        <span>Example review</span>
      </figcaption>
      <pre className="overflow-x-auto py-3 font-mono text-[13px] leading-6">
        {SAMPLE.map((line, i) => {
          const n = i + 1;
          const flagged = n === 7;
          return (
            <div key={n} className={flagged ? "bg-sev-high/12" : undefined}>
              <span className={`inline-block w-10 select-none pr-3 text-right ${flagged ? "text-sev-high" : "text-faint"}`}>{n}</span>
              <span className="text-ink/90">{line || " "}</span>
            </div>
          );
        })}
      </pre>
      <div className="m-3 mt-0 space-y-2 rounded-lg border border-sev-high/40 bg-night p-4">
        <div className="flex flex-wrap items-center gap-2">
          <SeverityBadge severity="HIGH" />
          <span className="text-sm font-semibold">Possible null dereference on line 7</span>
        </div>
        <p className="text-sm text-muted">
          <code className="font-mono text-ink">orElse(null)</code> returns null for unknown ids, and the next line calls{" "}
          <code className="font-mono text-ink">getName()</code> on it. Requests for a missing user fail with a 500.
        </p>
        <pre className="overflow-x-auto rounded bg-raised px-3 py-2 font-mono text-[12px] text-sage-strong">
          {"User user = users.findById(id)\n        .orElseThrow(() -> new UserNotFoundException(id));"}
        </pre>
      </div>
    </figure>
  );
}

const FEATURES = [
  { icon: Bug, title: "Bug detection", text: "Null dereferences, broken equality, swallowed exceptions, leaked resources and logic errors, each tied to the exact line." },
  { icon: ShieldAlert, title: "Security analysis", text: "Hard-coded secrets, injection-prone queries, unsafe deserialisation and disabled TLS checks, from deterministic rules and the model." },
  { icon: Gauge, title: "Performance review", text: "String building in loops, N+1 style access, needless allocations and concurrency hazards that cost you under load." },
  { icon: GitPullRequest, title: "Pull request reviews", text: "Only the changed lines are judged. Publish selected findings as review comments once you confirm. Nothing is ever approved or merged." },
];

const STEPS = [
  ["Connect GitHub", "Sign in with GitHub. CodeSage only sees the repositories your account can already read."],
  ["Pick a branch or pull request", "Choose what to review. Generated, vendored and binary files are skipped automatically."],
  ["Static analysis, then AI", "PMD and CodeSage's security rules run first; the language model then reviews bounded, line-numbered code."],
  ["Fix, track and compare", "Every finding explains the impact and a fix. Mark issues resolved and compare runs to see what changed."],
];

export default function Landing() {
  const { data: config } = useAuthConfig();
  const { data: me } = useMe();
  const ctaHref = me ? "/dashboard" : "/login";

  return (
    <div className="min-h-dvh">
      <header className="mx-auto flex max-w-6xl items-center justify-between px-5 py-5">
        <Logo className="text-lg" />
        <nav className="flex items-center gap-2 text-sm">
          <a href="#how" className="hidden px-3 py-2 text-muted hover:text-ink sm:inline">How it works</a>
          <a href={REPO_URL} className="hidden px-3 py-2 text-muted hover:text-ink sm:inline">Source</a>
          <Link to={ctaHref} className="rounded-md border border-line px-3 py-2 hover:border-line-strong">
            {me ? "Open dashboard" : "Sign in"}
          </Link>
        </nav>
      </header>

      <section className="mx-auto grid max-w-6xl items-center gap-12 px-5 pb-20 pt-10 lg:grid-cols-[1fr_1.1fr] lg:pt-16">
        <div>
          <h1 className="text-[clamp(2.4rem,5vw,3.75rem)] font-extrabold leading-[1.04] tracking-[-0.03em]">
            Code review that points at the line and explains why.
          </h1>
          <p className="mt-6 max-w-[34rem] text-lg leading-relaxed text-muted">
            CodeSage reads your GitHub repositories and pull requests, combines static analyzers with a language model, and
            returns findings you can act on: what is wrong, where, how bad it is, and the fix.
          </p>
          <div className="mt-8 flex flex-wrap items-center gap-3">
            <Link to={ctaHref} className="inline-flex items-center gap-2 rounded-md bg-sage px-5 py-3 font-semibold text-night hover:bg-sage-strong">
              <Github className="size-5" aria-hidden /> {me ? "Go to your dashboard" : "Connect GitHub"}
            </Link>
            <a href="#how" className="px-3 py-3 text-sm text-muted hover:text-ink">How it works</a>
          </div>
          {config && !config.githubConfigured && (
            <p className="mt-6 max-w-[34rem] rounded-md border border-pollen/40 bg-pollen/10 px-4 py-3 text-sm text-pollen">
              GitHub sign-in is not configured on this server yet. Set GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET (see the README).
            </p>
          )}
        </div>
        <HeroExample />
      </section>

      <section className="border-y border-line bg-surface/60">
        <div className="mx-auto grid max-w-6xl gap-x-12 gap-y-10 px-5 py-16 md:grid-cols-2">
          {FEATURES.map(({ icon: Icon, title, text }) => (
            <div key={title} className="flex gap-4">
              <Icon className="mt-1 size-6 shrink-0 text-sage" aria-hidden />
              <div>
                <h2 className="text-lg font-semibold">{title}</h2>
                <p className="mt-1 max-w-[30rem] leading-relaxed text-muted">{text}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-5 py-20">
        <div className="grid gap-12 lg:grid-cols-[1fr_1.2fr] lg:items-center">
          <div>
            <h2 className="text-3xl font-bold tracking-tight">Your dashboard, from your own reviews</h2>
            <p className="mt-4 max-w-[30rem] leading-relaxed text-muted">
              Scores, open critical issues, activity and the categories that keep coming back. Every number is computed from
              reviews you ran; nothing is pre-filled.
            </p>
          </div>
          <figure className="rounded-xl border border-line bg-surface p-5" aria-label="Illustrative dashboard preview">
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold">acme/payments-service · main</span>
              <span className="text-xs text-faint">Illustration</span>
            </div>
            <div className="mt-5 flex items-center gap-6">
              <ScoreDial score={74} size={72} />
              <dl className="grid flex-1 grid-cols-3 gap-4 text-sm">
                {[
                  ["Critical", 1, "text-sev-critical"],
                  ["High", 4, "text-sev-high"],
                  ["Resolved", 9, "text-sage"],
                ].map(([label, n, cls]) => (
                  <div key={label as string}>
                    <dt className="text-muted">{label}</dt>
                    <dd className={`text-2xl font-bold tabular-nums ${cls}`}>{n}</dd>
                  </div>
                ))}
              </dl>
            </div>
            <div className="mt-6 flex h-20 items-end gap-1.5" aria-hidden>
              {[2, 3, 1, 4, 6, 3, 5, 7, 4, 8, 6, 9, 5, 7].map((h, i) => (
                <div key={i} className="flex-1 rounded-sm bg-sage/70" style={{ height: `${h * 10}%` }} />
              ))}
            </div>
          </figure>
        </div>
      </section>

      <section id="how" className="mx-auto max-w-6xl px-5 pb-24">
        <h2 className="text-3xl font-bold tracking-tight">How it works</h2>
        <ol className="mt-10 grid gap-8 md:grid-cols-2 lg:grid-cols-4">
          {STEPS.map(([title, text], i) => (
            <li key={title} className="border-t-2 border-sage/60 pt-4">
              <span className="text-sm font-semibold text-sage">Step {i + 1}</span>
              <h3 className="mt-1 font-semibold">{title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-muted">{text}</p>
            </li>
          ))}
        </ol>
      </section>

      <footer className="border-t border-line">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-5 py-8 text-sm text-muted">
          <Logo />
          <div className="flex flex-wrap gap-5">
            <a href="/swagger-ui.html" className="hover:text-ink">API documentation</a>
            <a href={`${REPO_URL}#readme`} className="hover:text-ink">Docs</a>
            <a href={REPO_URL} className="inline-flex items-center gap-1.5 hover:text-ink">
              <Github className="size-4" aria-hidden /> GitHub
            </a>
          </div>
          <p className="w-full text-xs text-faint">
            <FileSearch className="mr-1 inline size-3.5" aria-hidden />
            AI findings are suggestions and are labelled as unverified. CodeSage never runs your code or pushes changes.
          </p>
        </div>
      </footer>
    </div>
  );
}
