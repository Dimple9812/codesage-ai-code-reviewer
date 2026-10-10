import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "../../api/client";
import { RequireAuth } from "../../components/RequireAuth";
import { detailFixture, findingFixture, renderWithProviders, summaryFixture } from "../../test/utils";

vi.mock("../../api/endpoints", () => ({
  api: {
    authConfig: vi.fn(),
    me: vi.fn(),
    repositories: vi.fn(),
    dashboardSummary: vi.fn(),
    activity: vi.fn(),
    distribution: vi.fn(),
    review: vi.fn(),
    reviewStatus: vi.fn(),
    findings: vi.fn(),
    reviewFiles: vi.fn(),
    fileContent: vi.fn(),
    branches: vi.fn(),
    pullRequests: vi.fn(),
    updateFinding: vi.fn(),
    publishComments: vi.fn(),
    cancelReview: vi.fn(),
  },
}));

vi.mock("../../components/CodeViewer", () => ({
  default: ({ path, focusLine }: { path: string; focusLine: number | null }) => (
    <div data-testid="code-viewer">
      viewing {path} at line {String(focusLine)}
    </div>
  ),
}));

import { api } from "../../api/endpoints";
import Dashboard from "../Dashboard";
import Login from "../Login";
import Repositories from "../Repositories";
import ReviewWorkspace, { filterFindings, parseRanges } from "../ReviewWorkspace";
import { validateSettings } from "../Settings";

const mocked = api as unknown as Record<string, ReturnType<typeof vi.fn>>;
const me = { id: "u1", login: "octocat", name: "Mona Octocat", email: null, avatarUrl: null, githubConnected: true, githubScopes: "repo", connectedAt: null };

beforeEach(() => {
  Object.values(mocked).forEach((fn) => fn.mockReset());
  mocked.authConfig.mockResolvedValue({ githubConfigured: true, aiConfigured: true, aiProvider: "anthropic", webhooksConfigured: false });
});

describe("authentication flow", () => {
  it("redirects anonymous users to the login page", async () => {
    mocked.me.mockRejectedValue(new ApiError(401, "UNAUTHENTICATED", "expired"));
    renderWithProviders(<RequireAuth />, { route: "/dashboard" });
    expect(await screen.findByText("login page")).toBeInTheDocument();
  });

  it("lets signed-in users through", async () => {
    mocked.me.mockResolvedValue(me);
    renderWithProviders(<RequireAuth />, { route: "/dashboard" });
    await waitFor(() => expect(screen.queryByText("login page")).not.toBeInTheDocument());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("explains a rejected OAuth state on the login page", async () => {
    mocked.me.mockRejectedValue(new ApiError(401, "UNAUTHENTICATED", "x"));
    renderWithProviders(<Login />, { route: "/login?error=invalid_state", path: "/login" });
    expect(await screen.findByRole("alert")).toHaveTextContent(/expired or did not originate here/);
    expect(screen.getByRole("link", { name: /continue with github/i })).toHaveAttribute("href", "/api/auth/github");
  });

  it("shows an expired-session notice", async () => {
    mocked.me.mockRejectedValue(new ApiError(401, "UNAUTHENTICATED", "x"));
    renderWithProviders(<Login />, { route: "/login?expired=1", path: "/login" });
    expect(await screen.findByText(/session expired/i)).toBeInTheDocument();
  });

  it("shows setup guidance instead of a broken button when GitHub is not configured", async () => {
    mocked.me.mockRejectedValue(new ApiError(401, "UNAUTHENTICATED", "x"));
    mocked.authConfig.mockResolvedValue({ githubConfigured: false, aiConfigured: false, aiProvider: "anthropic", webhooksConfigured: false });
    renderWithProviders(<Login />, { route: "/login", path: "/login" });
    expect(await screen.findByText("GitHub OAuth is not configured")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /continue with github/i })).not.toBeInTheDocument();
  });
});

describe("Dashboard", () => {
  beforeEach(() => {
    mocked.me.mockResolvedValue(me);
    mocked.activity.mockResolvedValue([{ date: "2026-10-01", reviews: 2 }]);
    mocked.repositories.mockResolvedValue([]);
  });

  it("renders metrics from the API", async () => {
    mocked.dashboardSummary.mockResolvedValue({
      repositoriesAccessible: 12,
      repositoriesReviewed: 3,
      totalReviews: 7,
      completedReviews: 6,
      activeReviews: 1,
      averageScore: 80,
      openCritical: 2,
      openHigh: 5,
      openFindings: 20,
      resolvedFindings: 4,
      dismissedFindings: 1,
      recentReviews: [summaryFixture()],
    });
    mocked.distribution.mockResolvedValue([{ category: "SECURITY", count: 4 }]);
    renderWithProviders(<Dashboard />);
    expect(await screen.findByText("12")).toBeInTheDocument();
    expect(screen.getByText("2 / 5")).toBeInTheDocument();
    expect(screen.getByText("1 in progress")).toBeInTheDocument();
    expect(screen.getByText("octo/api")).toBeInTheDocument();
    expect(await screen.findByText("Security")).toBeInTheDocument();
  });

  it("guides new users to their first review", async () => {
    mocked.dashboardSummary.mockResolvedValue({
      repositoriesAccessible: 0, repositoriesReviewed: 0, totalReviews: 0, completedReviews: 0, activeReviews: 0, averageScore: null,
      openCritical: 0, openHigh: 0, openFindings: 0, resolvedFindings: 0, dismissedFindings: 0, recentReviews: [],
    });
    mocked.distribution.mockResolvedValue([]);
    renderWithProviders(<Dashboard />);
    expect(await screen.findByText("Run your first review")).toBeInTheDocument();
    expect(screen.getByText(/categories appear after your first completed review/i)).toBeInTheDocument();
  });

  it("shows an error with retry when the API fails", async () => {
    mocked.dashboardSummary.mockRejectedValue(new ApiError(502, "GITHUB_UNAVAILABLE", "GitHub is currently unavailable."));
    mocked.distribution.mockResolvedValue([]);
    renderWithProviders(<Dashboard />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("GitHub is currently unavailable.");
    expect(within(alert).getByRole("button", { name: /try again/i })).toBeInTheDocument();
  });
});

describe("Repositories", () => {
  it("lists repositories and filters by visibility", async () => {
    mocked.repositories.mockResolvedValue([
      { id: "a", githubId: 1, fullName: "octo/public-lib", owner: "octo", name: "public-lib", description: "lib", language: "Java", isPrivate: false, defaultBranch: "main", htmlUrl: null, autoReview: false, pushedAt: null, lastSyncedAt: "" },
      { id: "b", githubId: 2, fullName: "octo/secret-svc", owner: "octo", name: "secret-svc", description: null, language: "Go", isPrivate: true, defaultBranch: "main", htmlUrl: null, autoReview: true, pushedAt: null, lastSyncedAt: "" },
    ]);
    renderWithProviders(<Repositories />);
    expect(await screen.findByText("octo/public-lib")).toBeInTheDocument();
    expect(screen.getByText("octo/secret-svc")).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Visibility"), "private");
    expect(screen.queryByText("octo/public-lib")).not.toBeInTheDocument();
    expect(screen.getByText("octo/secret-svc")).toBeInTheDocument();
  });

  it("shows an empty state", async () => {
    mocked.repositories.mockResolvedValue([]);
    renderWithProviders(<Repositories />);
    expect(await screen.findByText("No repositories found")).toBeInTheDocument();
  });

  it("asks the user to re-authenticate when GitHub access was revoked", async () => {
    mocked.repositories.mockRejectedValue(new ApiError(401, "GITHUB_REAUTH_REQUIRED", "Your GitHub authorization is missing, expired or was revoked."));
    renderWithProviders(<Repositories />);
    expect(await screen.findByText(/sign in with github again/i)).toBeInTheDocument();
  });
});

describe("ReviewWorkspace", () => {
  const route = { route: "/reviews/r1", path: "/reviews/:reviewId" };

  it("shows progress while a review is running", async () => {
    mocked.review.mockResolvedValue(detailFixture({ status: "RUNNING", score: null }));
    mocked.reviewStatus.mockResolvedValue({ id: "r1", status: "RUNNING", aiStatus: null, startedAt: null, completedAt: null, errorMessage: null, terminal: false });
    renderWithProviders(<ReviewWorkspace />, route);
    expect(await screen.findByText(/this page updates automatically/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /cancel review/i })).toBeInTheDocument();
    expect(mocked.findings).not.toHaveBeenCalled();
  });

  it("renders findings, filters them and navigates to the source line", async () => {
    mocked.review.mockResolvedValue(detailFixture());
    mocked.reviewStatus.mockResolvedValue({ id: "r1", status: "COMPLETED", aiStatus: "COMPLETED", startedAt: null, completedAt: null, errorMessage: null, terminal: true });
    mocked.findings.mockResolvedValue([
      findingFixture(),
      findingFixture({ id: "f2", severity: "MEDIUM", category: "SECURITY", title: "Hard-coded credential", source: "STATIC_ANALYSIS", analyzer: "codesage-rules", verification: "STATIC_ANALYZER_REPORTED", filePath: "config.py", startLine: 2, endLine: 2, suggestedCode: null }),
    ]);
    mocked.reviewFiles.mockResolvedValue([
      { path: "src/UserService.java", language: "java", analyzed: true, skipReason: null, changedLines: null, findingCount: 1 },
      { path: "config.py", language: "python", analyzed: true, skipReason: null, changedLines: null, findingCount: 1 },
      { path: "dist/app.min.js", language: "javascript", analyzed: false, skipReason: "Generated, vendored or lock file", changedLines: null, findingCount: 0 },
    ]);
    mocked.fileContent.mockImplementation(async (_id: string, path: string) => ({ path, language: "java", content: "class A {}", commitSha: "abc" }));

    renderWithProviders(<ReviewWorkspace />, route);
    expect(await screen.findByText("Possible null pointer exception")).toBeInTheDocument();
    expect(screen.getByText("Hard-coded credential")).toBeInTheDocument();
    expect(screen.getByText("Two issues found in the user service.")).toBeInTheDocument();
    expect(screen.getByText(/Findings/)).toHaveTextContent("(2 of 2)");

    await userEvent.selectOptions(screen.getByLabelText("Filter by severity"), "MEDIUM");
    expect(screen.queryByText("Possible null pointer exception")).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Filter by severity"), "");

    fireEvent.click(screen.getByText("Possible null pointer exception"));
    expect(await screen.findByText("Before")).toBeInTheDocument();
    expect(screen.getByText("Suggested")).toBeInTheDocument();
    expect(screen.getByText("return u.getName().trim();")).toBeInTheDocument();
    expect(await screen.findByTestId("code-viewer")).toHaveTextContent("viewing src/UserService.java at line 9");
  });

  it("marks a finding resolved", async () => {
    mocked.review.mockResolvedValue(detailFixture());
    mocked.reviewStatus.mockResolvedValue({ id: "r1", status: "COMPLETED", aiStatus: "COMPLETED", startedAt: null, completedAt: null, errorMessage: null, terminal: true });
    mocked.findings.mockResolvedValue([findingFixture()]);
    mocked.reviewFiles.mockResolvedValue([]);
    mocked.fileContent.mockResolvedValue({ path: "src/UserService.java", language: "java", content: "", commitSha: "abc" });
    mocked.updateFinding.mockResolvedValue(findingFixture({ status: "RESOLVED" }));
    renderWithProviders(<ReviewWorkspace />, route);
    fireEvent.click(await screen.findByText("Possible null pointer exception"));
    await userEvent.click(await screen.findByRole("button", { name: "Mark resolved" }));
    await waitFor(() => expect(mocked.updateFinding).toHaveBeenCalledWith("f1", "RESOLVED"));
  });

  it("requires explicit confirmation before publishing PR comments", async () => {
    mocked.review.mockResolvedValue(detailFixture({ type: "PULL_REQUEST", pullRequestNumber: 7 }));
    mocked.reviewStatus.mockResolvedValue({ id: "r1", status: "COMPLETED", aiStatus: "COMPLETED", startedAt: null, completedAt: null, errorMessage: null, terminal: true });
    mocked.findings.mockResolvedValue([findingFixture({ inDiff: true })]);
    mocked.reviewFiles.mockResolvedValue([]);
    mocked.fileContent.mockResolvedValue({ path: "src/UserService.java", language: "java", content: "", commitSha: "abc" });
    mocked.publishComments.mockResolvedValue({ published: 1, skipped: 0, githubReviewUrl: "https://github.com/x" });
    renderWithProviders(<ReviewWorkspace />, route);

    const publish = await screen.findByRole("button", { name: /publish comments/i });
    expect(publish).toBeDisabled();
    await userEvent.click(await screen.findByLabelText(/select "possible null pointer exception" for publishing/i));
    await userEvent.click(publish);
    expect(mocked.publishComments).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent(/nothing is approved, blocked, merged or changed/i);
    await userEvent.click(within(dialog).getByRole("button", { name: "Publish comments" }));
    await waitFor(() => expect(mocked.publishComments).toHaveBeenCalledWith("r1", ["f1"]));
  });

  it("explains failed reviews", async () => {
    mocked.review.mockResolvedValue({ ...detailFixture({ status: "FAILED" }), errorMessage: "GitHub API rate limit reached." });
    mocked.reviewStatus.mockResolvedValue({ id: "r1", status: "FAILED", aiStatus: null, startedAt: null, completedAt: null, errorMessage: "GitHub API rate limit reached.", terminal: true });
    renderWithProviders(<ReviewWorkspace />, route);
    expect(await screen.findByRole("alert")).toHaveTextContent("GitHub API rate limit reached.");
  });
});

describe("pure helpers", () => {
  it("parses compact line ranges", () => {
    expect([...parseRanges("3-5,9")]).toEqual([3, 4, 5, 9]);
    expect(parseRanges(null).size).toBe(0);
  });

  it("filters findings by every dimension", () => {
    const list = [findingFixture(), findingFixture({ id: "2", severity: "LOW", source: "STATIC_ANALYSIS", filePath: "b.py" })];
    expect(filterFindings(list, { severity: "LOW", category: "", source: "", status: "" }, null)).toHaveLength(1);
    expect(filterFindings(list, { severity: "", category: "", source: "AI", status: "" }, null)[0].id).toBe("f1");
    expect(filterFindings(list, { severity: "", category: "", source: "", status: "" }, "b.py")[0].id).toBe("2");
  });

  it("validates settings before saving", () => {
    expect(validateSettings({ aiModel: "claude-sonnet-5-5", enabledLanguages: ["java"], maxFiles: 10 }, 60)).toEqual({});
    const errors = validateSettings({ aiModel: "bad model;", enabledLanguages: [], maxFiles: 0 }, 60);
    expect(Object.keys(errors).sort()).toEqual(["aiModel", "enabledLanguages", "maxFiles"]);
  });
});
