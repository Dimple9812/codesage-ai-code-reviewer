import { Github, ShieldCheck } from "lucide-react";
import { Navigate, useSearchParams } from "react-router";

import { useAuthConfig, useMe } from "../api/hooks";
import { Logo } from "../components/Logo";

const ERRORS: Record<string, string> = {
  invalid_state: "The sign-in attempt expired or did not originate here, so it was rejected. Start again below.",
  access_denied: "GitHub authorization was cancelled.",
  oauth_failed: "GitHub sign-in failed. Try again, and check the OAuth app's callback URL if it keeps failing.",
  github_not_configured: "GitHub sign-in is not configured on this server.",
};

export default function Login() {
  const [params] = useSearchParams();
  const { data: me } = useMe();
  const { data: config, isLoading } = useAuthConfig();
  const error = params.get("error");
  const expired = params.get("expired");

  if (me) return <Navigate to={params.get("next") ?? "/dashboard"} replace />;

  return (
    <div className="grid min-h-dvh place-items-center px-5 py-10">
      <div className="w-full max-w-md">
        <Logo className="text-xl" />
        <h1 className="mt-10 text-3xl font-bold tracking-tight">Sign in with GitHub</h1>
        <p className="mt-3 leading-relaxed text-muted">
          CodeSage uses GitHub OAuth. Your access token stays encrypted on the server and is never sent to this browser.
        </p>

        {expired && !error && (
          <p role="status" className="mt-6 rounded-md border border-pollen/40 bg-pollen/10 px-4 py-3 text-sm text-pollen">
            Your session expired. Sign in again to continue.
          </p>
        )}
        {error && (
          <p role="alert" className="mt-6 rounded-md border border-sev-critical/40 bg-sev-critical/10 px-4 py-3 text-sm">
            {ERRORS[error] ?? ERRORS.oauth_failed}
          </p>
        )}

        {!isLoading && config && !config.githubConfigured ? (
          <div className="mt-8 space-y-3 rounded-lg border border-line bg-surface p-5 text-sm">
            <p className="font-semibold">GitHub OAuth is not configured</p>
            <ol className="list-decimal space-y-1.5 pl-5 text-muted">
              <li>Create an OAuth App at github.com/settings/developers.</li>
              <li>
                Set the callback URL to <code className="font-mono text-ink">{window.location.origin}/login/oauth2/code/github</code>.
              </li>
              <li>Put GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET in your .env and restart the backend.</li>
            </ol>
          </div>
        ) : (
          <a
            href="/api/auth/github"
            className="mt-8 flex w-full items-center justify-center gap-2.5 rounded-md bg-sage px-5 py-3 font-semibold text-night hover:bg-sage-strong"
          >
            <Github className="size-5" aria-hidden /> Continue with GitHub
          </a>
        )}

        <ul className="mt-8 space-y-2 text-sm text-muted">
          {[
            "Read access to repositories your account can see; private repos only if you grant the repo scope.",
            "Nothing is pushed, approved or merged. PR comments are posted only when you confirm.",
            "Source files are processed in memory and not stored.",
          ].map((t) => (
            <li key={t} className="flex gap-2">
              <ShieldCheck className="mt-0.5 size-4 shrink-0 text-sage" aria-hidden /> {t}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
