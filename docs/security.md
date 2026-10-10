# Security model

## Authentication & sessions
- GitHub OAuth App via Spring Security's OAuth2 client. The authorization request (including `state`) is stored in the server session, and callbacks with a missing or forged `state` are rejected (`/login?error=invalid_state`). This is covered by `AuthIntegrationTest`.
- After login only the CodeSage user id is kept in the session principal. The access token is encrypted (AES-256-GCM, random 96-bit IV per value) into `github_connections` and is never sent to the browser.
- Session cookie `CODESAGE_SESSION`: `HttpOnly`, `SameSite=Lax`, and `Secure` when `SESSION_COOKIE_SECURE=true`. Spring's default session-fixation protection applies.
- API calls without a session return a JSON `401`, which the SPA turns into "session expired". A revoked or expired GitHub grant surfaces as `GITHUB_REAUTH_REQUIRED`.

## Authorisation
- Every repository, review and finding lookup is scoped by the authenticated user id (`findByIdAndUserId`, `findOwned`). Foreign ids return `404`.
- GitHub calls always use the requesting user's token, so GitHub enforces repository permissions too.
- `TenantIsolationIntegrationTest` exercises every endpoint with another user's ids.

## CSRF & CORS
- `CookieCsrfTokenRepository` + SPA token handler. Mutations need `X-XSRF-TOKEN`.
- `/api/webhooks/**` is CSRF-exempt and authenticated by `X-Hub-Signature-256` (HMAC-SHA256, constant-time compare).
- CORS is limited to `APP_CORS_ALLOWED_ORIGINS`, with credentials allowed. In the standard deployment nginx makes everything same-origin.

## Input handling
- Bean Validation on all request bodies; branch names, models, analyzers and languages use allow-list patterns.
- File content requests must refer to a path recorded for that review; `..` and absolute paths are rejected.
- No user-controlled outbound URLs. Custom LLM endpoints are server configuration only.

## Untrusted repository content
- Code is never executed. PMD parses in-process; Semgrep (optional) scans temporary copies with a scrubbed environment and a timeout.
- Prompt injection: files are wrapped in `<untrusted_file>` tags and the system prompt declares them data, not instructions. The response schema and the path/line checks bound what the model can make CodeSage do: it can only produce findings.
- Rendering: `react-markdown` without raw HTML, `javascript:` links stripped, images disabled. Text published to GitHub is HTML-escaped and `@mentions` are neutralised.

## Secrets
- Only `.env.example` (placeholders) is committed; `.gitignore` excludes `.env*`.
- Personal AI keys are encrypted and displayed only as `••••` + the last 4 characters.
- Tokens and keys never appear in logs or error responses (`AnthropicProviderTest.apiKeyNeverAppearsInErrorMessages`).

## Abuse protection
- Per-user request rate limiting (`RATE_LIMIT_RPM`) and review-creation limiting (`RATE_LIMIT_REVIEWS_PER_HOUR`); use the Redis backend when running more than one instance.
- Bounded executor queue, file, byte and request limits per review.

## Operations
- Only `/actuator/health` and `/actuator/info` are exposed, and nginx only proxies `/actuator/health`.
- Dependabot, `npm audit` in CI, and `mvn -Psecurity-scan verify` (OWASP Dependency-Check, fails on CVSS ≥ 7).
