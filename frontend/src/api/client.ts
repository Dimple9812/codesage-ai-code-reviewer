import axios, { AxiosError } from "axios";

/** Normalised error shape for every failed API call. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: Record<string, string>;
  readonly correlationId?: string;

  constructor(status: number, code: string, message: string, fieldErrors: Record<string, string> = {}, correlationId?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.correlationId = correlationId;
  }

  get isAuthError(): boolean {
    return this.status === 401;
  }
}

interface ErrorBody {
  code?: string;
  message?: string;
  fieldErrors?: Record<string, string>;
  correlationId?: string;
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (error instanceof AxiosError) {
    if (!error.response) {
      return new ApiError(0, "NETWORK_ERROR", "Can't reach the CodeSage server. Check that the backend is running.");
    }
    const body = (error.response.data ?? {}) as ErrorBody;
    const status = error.response.status;
    const fallback =
      status === 429
        ? "Too many requests. Wait a moment and try again."
        : status >= 500
          ? "The server hit an unexpected error."
          : "The request could not be completed.";
    return new ApiError(status, body.code ?? `HTTP_${status}`, body.message ?? fallback, body.fieldErrors ?? {}, body.correlationId);
  }
  return new ApiError(0, "UNKNOWN", error instanceof Error ? error.message : "Unknown error");
}

export const http = axios.create({
  baseURL: "/api",
  withCredentials: true,
  // Spring Security issues the XSRF-TOKEN cookie; axios echoes it in X-XSRF-TOKEN on mutations.
  xsrfCookieName: "XSRF-TOKEN",
  xsrfHeaderName: "X-XSRF-TOKEN",
  timeout: 60_000,
  headers: { Accept: "application/json" },
});

type AuthListener = (error: ApiError) => void;
const authListeners = new Set<AuthListener>();

/** Lets the app react globally to an expired session or revoked GitHub authorisation. */
export function onAuthFailure(listener: AuthListener): () => void {
  authListeners.add(listener);
  return () => authListeners.delete(listener);
}

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const apiError = toApiError(error);
    if (apiError.status === 401) {
      authListeners.forEach((l) => l(apiError));
    }
    return Promise.reject(apiError);
  },
);

/** Make sure the CSRF cookie exists before the first mutation (any GET issues it). */
export async function ensureCsrfCookie(): Promise<void> {
  if (!document.cookie.includes("XSRF-TOKEN=")) {
    await http.get("/auth/config").catch(() => undefined);
  }
}
