# Deployment guide

The application has **not** been deployed by the authors. These are tested build artifacts plus instructions.

## Free deployment: Render + Neon (single container)

The root `Dockerfile` bundles the React build into the Spring Boot app, so one free Render web service serves both the UI and the API (same origin, no CORS, no nginx). `render.yaml` describes the service.

1. **Neon** (free Postgres): create a project and copy the connection details. Use
   `DATABASE_URL=jdbc:postgresql://<host>/<db>?sslmode=require` plus `DATABASE_USERNAME` / `DATABASE_PASSWORD`.
2. **Render**: New → Blueprint → select this GitHub repository. Render reads `render.yaml`, builds the root `Dockerfile` and deploys on every push to `main`.
3. Fill in the env vars Render asks for. `APP_PUBLIC_URL`, `APP_FRONTEND_URL` and `APP_CORS_ALLOWED_ORIGINS` are all `https://<service>.onrender.com`.
4. Create a **separate GitHub OAuth App** for production with callback `https://<service>.onrender.com/login/oauth2/code/github`.

Free-tier caveats: the instance sleeps after 15 minutes without traffic, and the first request takes about a minute to wake it. Sessions are in memory, so users sign in again after a restart. There are 750 instance hours per month.

## Images
```bash
docker build -t codesage-backend ./backend
docker build -t codesage-frontend ./frontend
```
In the frontend image, nginx expects the backend at `backend:8080`. Adjust the `upstream` in `frontend/nginx.conf` for other topologies.

## Required production settings
| Variable | Value |
| --- | --- |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Managed PostgreSQL 16, private network |
| `TOKEN_ENCRYPTION_KEY` | `openssl rand -base64 32`, stored in a secret manager. Rotating it signs every user out of GitHub (tokens become unreadable). |
| `APP_PUBLIC_URL`, `APP_FRONTEND_URL`, `APP_CORS_ALLOWED_ORIGINS` | `https://your-domain` |
| `SESSION_COOKIE_SECURE` | `true` |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | OAuth App with callback `https://your-domain/login/oauth2/code/github` |
| `GITHUB_WEBHOOK_SECRET` | optional |
| `AI_PROVIDER`, `AI_MODEL`, `AI_API_KEY` | optional |
| `CACHE_TYPE=redis`, `RATE_LIMIT_BACKEND=redis`, `REDIS_HOST` | when running more than one backend instance |
| `SWAGGER_ENABLED` | `false` unless the docs should be public |
| `LOG_FORMAT` | `ecs` for JSON logs |

## Platform notes
- **Railway / Render / Fly.io:** deploy two services from the two Dockerfiles plus managed Postgres (and Redis). Expose only the frontend service publicly; give the backend a private hostname named `backend`, or edit `nginx.conf`.
- **Kubernetes:** use `/actuator/health/liveness` and `/actuator/health/readiness` for probes. Keep Postgres/Redis as `ClusterIP` services and put secrets in `Secret` objects or an external secret manager.

## Database migrations
Flyway runs on startup. For rolling deploys, keep migrations backwards compatible (expand → migrate → contract) and let one instance apply them before scaling out.

## Monitoring
- Every response includes `X-Correlation-Id`; the same id appears in logs (MDC) and error bodies.
- Alert on `FAILED` reviews, LLM `RATE_LIMITED` warnings and GitHub `GITHUB_RATE_LIMITED` responses.
- Ship JSON logs (`LOG_FORMAT=ecs`) to your log platform; tokens and keys are never logged.
