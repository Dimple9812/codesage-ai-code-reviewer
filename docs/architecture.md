# Architecture

## Backend layers

| Layer | Responsibility | Examples |
| --- | --- | --- |
| Controllers | HTTP, validation, mapping to DTOs | `ReviewController`, `RepositoryController` |
| Services | Business rules, transactions, **ownership checks** | `ReviewService.requireOwned`, `RepositoryService.requireOwned` |
| Integrations | All outbound calls | `GitHubClient`, `AnthropicProvider`, `OpenAiCompatibleProvider` |
| Pipeline | Orchestrates one review (non-transactional) | `ReviewPipeline`, `ReviewStateService` (short transactions) |
| Persistence | JPA entities + Spring Data repositories, Flyway schema | `CodeReview`, `ReviewFinding`, `V1__initial_schema.sql` |

JPA entities are never returned from controllers; every endpoint maps to records in `*Dtos`.
`GlobalExceptionHandler` turns `ApiException` subclasses into the shared `ErrorResponse`.

## Job processing

1. `ReviewService.create` inserts `code_reviews` + `review_jobs` in one transaction.
2. After commit, `ReviewJobService` dispatches the job id to a bounded `ThreadPoolTaskExecutor` (`REVIEW_WORKER_THREADS`).
3. `ReviewJobRunner` claims it with `UPDATE review_jobs SET status='RUNNING' … WHERE id=? AND status='QUEUED'`. Only one instance can win, even with several application replicas.
4. A `@Scheduled` poller re-dispatches due retries and recovers jobs stuck in `RUNNING` longer than `stale-job-timeout`, for example after a crash.
5. Transient GitHub errors are retried with exponential backoff (30 s, 60 s, …), up to `REVIEW_JOB_MAX_ATTEMPTS`.

## Source collection

- **Repository review:** the branch is resolved to a commit SHA. The recursive git tree is filtered by `FileFilter`, which excludes `node_modules`, `vendor`, `dist`, `build`, `target`, lock files, minified and generated files (`@generated`, `DO NOT EDIT` markers), binaries and unsupported types. Remaining files are prioritised (application source → tests → SQL → config) and fetched until the count and byte limits are reached.
- **PR review:** files come from `/pulls/{n}/files`. `DiffParser` maps each patch to new-side *added* lines and *visible* (commentable) lines. File content is fetched at the PR head SHA.

## AI review

- Files are rendered with line numbers. In PR mode, changed lines are prefixed with `>`; files that are too large are windowed to ±15 lines around the changes.
- The rendered files are packed greedily into requests of at most `AI_MAX_CHARS_PER_REQUEST`, with at most `AI_MAX_REQUESTS_PER_REVIEW` requests. Files beyond that are listed in the review notes and are still covered by static analysis.
- Each response must be a single JSON object matching `AiReviewPayload` (Bean Validation: enums, ranges, lengths). Malformed output triggers one corrective retry (`AI_MAX_SCHEMA_RETRIES`).
- Transient provider failures (timeouts, 429 with `Retry-After`, 5xx/529) are retried by `LlmClient` with capped exponential backoff and jitter. Authentication and bad-request errors are not retried.
- Findings referencing files the model was not shown are discarded. Line numbers beyond the end of the file are dropped.

## Normalisation

`FindingNormalizer`:

- applies the user's minimum severity;
- removes exact duplicates, and drops AI findings that overlap a static finding (same file, category, ±1 line);
- attaches an excerpt of at most 15 lines, redacting secrets;
- flags `inDiff` for PR findings;
- computes a fingerprint from file, category, static rule id and the *content* of the first flagged line. Comparisons therefore survive code moving to other lines.

## Frontend

- `src/api/client.ts`: Axios instance (cookies, CSRF header, `ApiError` normalisation, global 401 listeners).
- `src/api/hooks.ts`: TanStack Query hooks. Review status polls every 2 s until terminal, then invalidates the dependent queries.
- `src/components/CodeViewer.tsx`: Monaco, bundled locally (no CDN), read-only, with severity decorations and changed-line gutters. It is lazy-loaded.
- Routing: public `/`, `/login`; protected app routes under `RequireAuth` + `AppShell`.
