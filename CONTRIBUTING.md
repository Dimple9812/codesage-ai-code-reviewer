# Contributing to CodeSage AI

Thanks for your interest in improving CodeSage AI.

## Development setup

1. Java 21, Maven 3.9+, Node.js 22 and (optionally) Docker.
2. `cp .env.example .env` and fill in the values you need (see the README).
3. Backend: `cd backend && mvn spring-boot:run` (needs PostgreSQL; `docker compose up postgres redis` works if you publish the ports locally).
4. Frontend: `cd frontend && npm ci && npm run dev` (proxies `/api` to `localhost:8080`).

Without a GitHub OAuth App you can still exercise the UI with the local harness described in
`docs/local-harness.md`.

## Before opening a pull request

```bash
cd backend && mvn verify          # unit + integration tests (Testcontainers test runs when Docker is available)
cd frontend && npm run typecheck && npm test && npm run build
```

- Keep business logic in services, HTTP concerns in controllers, external calls in clients.
- Every endpoint that takes a resource id must check ownership (see `TenantIsolationIntegrationTest`).
- Never log or return tokens, API keys or repository source.
- Add tests for behaviour changes; mock GitHub and LLM providers (see `support/GitHubStub`, `StubLlmProvider`).
- Use conventional, descriptive commit messages.

## Reporting security issues

Please do not open public issues for vulnerabilities. Contact the maintainer privately via GitHub.
