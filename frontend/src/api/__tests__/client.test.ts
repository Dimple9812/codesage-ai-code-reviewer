import { AxiosError, AxiosHeaders } from "axios";
import { describe, expect, it, vi } from "vitest";

import { ApiError, http, onAuthFailure, toApiError } from "../client";

function axiosError(status: number | null, data?: unknown) {
  const err = new AxiosError("fail");
  if (status !== null) {
    err.response = { status, data, statusText: "", headers: {}, config: { headers: new AxiosHeaders() } };
  }
  return err;
}

describe("toApiError", () => {
  it("maps backend error bodies including field errors and correlation id", () => {
    const e = toApiError(axiosError(400, { code: "VALIDATION_FAILED", message: "Request validation failed", fieldErrors: { branch: "invalid" }, correlationId: "abc12345" }));
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(400);
    expect(e.code).toBe("VALIDATION_FAILED");
    expect(e.fieldErrors.branch).toBe("invalid");
    expect(e.correlationId).toBe("abc12345");
  });

  it("explains network failures", () => {
    const e = toApiError(axiosError(null));
    expect(e.code).toBe("NETWORK_ERROR");
    expect(e.message).toMatch(/can't reach/i);
  });

  it("falls back to friendly messages when the body is missing", () => {
    expect(toApiError(axiosError(429)).message).toMatch(/too many requests/i);
    expect(toApiError(axiosError(503)).message).toMatch(/unexpected error/i);
  });
});

describe("auth failure listeners", () => {
  it("are notified on 401 responses", async () => {
    const listener = vi.fn();
    const off = onAuthFailure(listener);
    http.defaults.adapter = async () => {
      throw axiosError(401, { code: "UNAUTHENTICATED", message: "expired" });
    };
    await expect(http.get("/auth/me")).rejects.toMatchObject({ status: 401, code: "UNAUTHENTICATED" });
    expect(listener).toHaveBeenCalledWith(expect.objectContaining({ code: "UNAUTHENTICATED" }));
    off();
  });

  it("sends credentials and uses the Spring CSRF cookie/header names", () => {
    expect(http.defaults.withCredentials).toBe(true);
    expect(http.defaults.xsrfCookieName).toBe("XSRF-TOKEN");
    expect(http.defaults.xsrfHeaderName).toBe("X-XSRF-TOKEN");
  });
});
