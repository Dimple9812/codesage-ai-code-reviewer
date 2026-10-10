import { ensureCsrfCookie, http } from "./client";
import type {
  ActivityPoint,
  AuthConfig,
  Branch,
  CategoryCount,
  Commit,
  Comparison,
  CreateReviewRequest,
  DashboardSummary,
  FileContent,
  Finding,
  FindingStatus,
  Me,
  Page,
  PublishResult,
  PullRequest,
  Repository,
  ReviewDetail,
  ReviewFile,
  ReviewStatusInfo,
  ReviewSummary,
  Settings,
  UpdateSettings,
} from "./types";

const data = <T,>(p: Promise<{ data: T }>) => p.then((r) => r.data);

async function mutate<T>(fn: () => Promise<{ data: T }>): Promise<T> {
  await ensureCsrfCookie();
  return data(fn());
}

export interface ReviewFilters {
  repositoryId?: string;
  status?: string;
  branch?: string;
  severity?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface FindingFilters {
  severity?: string;
  category?: string;
  filePath?: string;
  source?: string;
  status?: string;
}

const clean = <T extends object>(o: T) =>
  Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== "" && v !== null));

export const api = {
  authConfig: () => data(http.get<AuthConfig>("/auth/config")),
  me: () => data(http.get<Me>("/auth/me")),
  logout: () => mutate(() => http.post<void>("/auth/logout")),
  disconnect: (deleteData: boolean) => mutate(() => http.delete<void>("/auth/account", { params: { deleteData } })),

  repositories: (q?: string, language?: string, refresh = false) =>
    data(http.get<Repository[]>("/repositories", { params: clean({ q, language, refresh: refresh || undefined }) })),
  repository: (id: string) => data(http.get<Repository>(`/repositories/${id}`)),
  branches: (id: string) => data(http.get<Branch[]>(`/repositories/${id}/branches`)),
  commits: (id: string, branch?: string) => data(http.get<Commit[]>(`/repositories/${id}/commits`, { params: clean({ branch }) })),
  pullRequests: (id: string, state = "open") =>
    data(http.get<PullRequest[]>(`/repositories/${id}/pull-requests`, { params: { state } })),
  setAutoReview: (id: string, enabled: boolean) =>
    mutate(() => http.put<Repository>(`/repositories/${id}/auto-review`, { enabled })),

  createReview: (body: CreateReviewRequest, idempotencyKey: string) =>
    mutate(() => http.post<ReviewSummary>("/reviews", body, { headers: { "Idempotency-Key": idempotencyKey } })),
  reviews: (filters: ReviewFilters) => data(http.get<Page<ReviewSummary>>("/reviews", { params: clean(filters) })),
  review: (id: string) => data(http.get<ReviewDetail>(`/reviews/${id}`)),
  reviewStatus: (id: string) => data(http.get<ReviewStatusInfo>(`/reviews/${id}/status`)),
  cancelReview: (id: string) => mutate(() => http.post<ReviewStatusInfo>(`/reviews/${id}/cancel`)),
  findings: (id: string, filters: FindingFilters = {}) =>
    data(http.get<Finding[]>(`/reviews/${id}/findings`, { params: clean(filters) })),
  reviewFiles: (id: string) => data(http.get<ReviewFile[]>(`/reviews/${id}/files`)),
  fileContent: (id: string, path: string) =>
    data(http.get<FileContent>(`/reviews/${id}/files/content`, { params: { path } })),
  compare: (id: string, baseReviewId?: string) =>
    data(http.get<Comparison>(`/reviews/${id}/compare`, { params: clean({ baseReviewId }) })),
  publishComments: (id: string, findingIds: string[]) =>
    mutate(() => http.post<PublishResult>(`/reviews/${id}/publish-comments`, { confirm: true, findingIds })),
  updateFinding: (id: string, status: FindingStatus) => mutate(() => http.patch<Finding>(`/findings/${id}`, { status })),

  dashboardSummary: () => data(http.get<DashboardSummary>("/dashboard/summary")),
  activity: (days = 30) => data(http.get<ActivityPoint[]>("/dashboard/activity", { params: { days } })),
  distribution: () => data(http.get<CategoryCount[]>("/dashboard/issue-distribution")),

  settings: () => data(http.get<Settings>("/settings")),
  updateSettings: (body: UpdateSettings) => mutate(() => http.put<Settings>("/settings", body)),
  setApiKey: (apiKey: string) => mutate(() => http.put<Settings>("/settings/ai-key", { apiKey })),
  clearApiKey: () => mutate(() => http.delete<Settings>("/settings/ai-key")),
};
