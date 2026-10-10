import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter, Route, Routes } from "react-router";

import type { Finding, ReviewDetail, ReviewSummary } from "../api/types";

export function renderWithProviders(ui: ReactElement, { route = "/", path = "*" }: { route?: string; path?: string } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path={path} element={ui} />
            {path !== "/login" && <Route path="/login" element={<div>login page</div>} />}
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

export const summaryFixture = (over: Partial<ReviewSummary> = {}): ReviewSummary => ({
  id: "r1",
  repositoryId: "repo1",
  repositoryFullName: "octo/api",
  type: "REPOSITORY",
  branch: "main",
  commitSha: "abcdef1234567",
  pullRequestNumber: null,
  pullRequestTitle: null,
  status: "COMPLETED",
  triggerSource: "MANUAL",
  score: 81,
  aiStatus: "COMPLETED",
  counts: { critical: 0, high: 1, medium: 1, low: 0, info: 0 },
  totalFindings: 2,
  createdAt: "2026-10-01T10:00:00Z",
  completedAt: "2026-10-01T10:01:00Z",
  ...over,
});

export const detailFixture = (over: Partial<ReviewSummary> = {}): ReviewDetail => ({
  review: summaryFixture(over),
  summary: "Two issues found in the user service.",
  notes: ["1 file(s) were not reviewed because of the configured limits."],
  errorMessage: null,
  aiProvider: "anthropic",
  aiModel: "claude-sonnet-5-5",
  filesConsidered: 3,
  filesAnalyzed: 2,
  filesSkipped: 1,
  startedAt: "2026-10-01T10:00:01Z",
  repositoryHtmlUrl: "https://github.com/octo/api",
  pullRequestHtmlUrl: null,
  scoreFormula: "formula",
});

export const findingFixture = (over: Partial<Finding> = {}): Finding => ({
  id: "f1",
  reviewId: "r1",
  source: "AI",
  analyzer: "llm",
  verification: "UNVERIFIED",
  category: "BUG",
  severity: "HIGH",
  confidence: 0.85,
  title: "Possible null pointer exception",
  filePath: "src/UserService.java",
  startLine: 9,
  endLine: 9,
  description: "getName() may return null.",
  impact: "HTTP 500 for unknown users.",
  recommendation: "Guard against null.",
  suggestedCode: "return name == null ? \"\" : name.trim();",
  originalCode: "return u.getName().trim();",
  ruleId: "JAVA-NULL-001",
  status: "OPEN",
  inDiff: false,
  publishedAt: null,
  createdAt: "2026-10-01T10:01:00Z",
  ...over,
});
