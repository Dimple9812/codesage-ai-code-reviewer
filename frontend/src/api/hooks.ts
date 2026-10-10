import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { toast } from "sonner";

import { ApiError } from "./client";
import { api, type FindingFilters, type ReviewFilters } from "./endpoints";
import type { CreateReviewRequest, FindingStatus, UpdateSettings } from "./types";
import { newIdempotencyKey } from "../lib/format";

export const keys = {
  config: ["auth-config"] as const,
  me: ["me"] as const,
  repositories: (q?: string, language?: string) => ["repositories", q ?? "", language ?? ""] as const,
  repository: (id: string) => ["repository", id] as const,
  branches: (id: string) => ["repository", id, "branches"] as const,
  commits: (id: string, branch?: string) => ["repository", id, "commits", branch ?? ""] as const,
  pulls: (id: string) => ["repository", id, "pulls"] as const,
  reviews: (f: ReviewFilters) => ["reviews", f] as const,
  review: (id: string) => ["review", id] as const,
  reviewStatus: (id: string) => ["review", id, "status"] as const,
  findings: (id: string, f: FindingFilters = {}) => ["review", id, "findings", f] as const,
  files: (id: string) => ["review", id, "files"] as const,
  file: (id: string, path: string) => ["review", id, "file", path] as const,
  compare: (id: string, base?: string) => ["review", id, "compare", base ?? ""] as const,
  dashboard: ["dashboard"] as const,
  settings: ["settings"] as const,
};

export const useAuthConfig = () => useQuery({ queryKey: keys.config, queryFn: api.authConfig, staleTime: 5 * 60_000 });

export const useMe = () =>
  useQuery({
    queryKey: keys.me,
    queryFn: async () => {
      try {
        return await api.me();
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: 60_000,
    retry: false,
  });

export const useRepositories = (q?: string, language?: string) =>
  useQuery({
    queryKey: keys.repositories(q, language),
    queryFn: () => api.repositories(q, language),
    placeholderData: keepPreviousData,
  });

export function useRefreshRepositories() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.repositories(undefined, undefined, true),
    onSuccess: (repos) => {
      qc.invalidateQueries({ queryKey: ["repositories"] });
      toast.success(`Synced ${repos.length} repositories from GitHub`);
    },
    onError: (e: ApiError) => toast.error(e.message),
  });
}

export const useRepository = (id: string) => useQuery({ queryKey: keys.repository(id), queryFn: () => api.repository(id) });
export const useBranches = (id: string) => useQuery({ queryKey: keys.branches(id), queryFn: () => api.branches(id) });
export const useCommits = (id: string, branch?: string) =>
  useQuery({ queryKey: keys.commits(id, branch), queryFn: () => api.commits(id, branch), enabled: !!branch });
export const usePullRequests = (id: string) => useQuery({ queryKey: keys.pulls(id), queryFn: () => api.pullRequests(id) });

export function useSetAutoReview(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (enabled: boolean) => api.setAutoReview(id, enabled),
    onSuccess: (repo) => {
      qc.setQueryData(keys.repository(id), repo);
      qc.invalidateQueries({ queryKey: ["repositories"] });
      toast.success(repo.autoReview ? "Automatic pull request reviews turned on" : "Automatic pull request reviews turned off");
    },
    onError: (e: ApiError) => toast.error(e.message),
  });
}

export function useCreateReview() {
  const qc = useQueryClient();
  return useMutation({
    // One key per mutation instance: double clicks and retries reuse it, so the server never queues twice.
    mutationFn: ({ body, key }: { body: CreateReviewRequest; key?: string }) => api.createReview(body, key ?? newIdempotencyKey()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["reviews"] });
      qc.invalidateQueries({ queryKey: keys.dashboard });
    },
  });
}

export const useReviews = (filters: ReviewFilters) =>
  useQuery({ queryKey: keys.reviews(filters), queryFn: () => api.reviews(filters), placeholderData: keepPreviousData });

export const useReview = (id: string) => useQuery({ queryKey: keys.review(id), queryFn: () => api.review(id) });

/** Polls while a review is queued/running, then refreshes the queries that depend on its result. */
export function useReviewStatus(id: string) {
  const qc = useQueryClient();
  const query = useQuery({
    queryKey: keys.reviewStatus(id),
    queryFn: () => api.reviewStatus(id),
    refetchInterval: (q) => (q.state.data?.terminal ? false : 2000),
  });
  const terminal = query.data?.terminal;
  const previous = useRef<boolean | undefined>(undefined);
  useEffect(() => {
    if (terminal && previous.current === false) {
      // Only dependent queries: invalidating the status query itself here would restart polling.
      qc.invalidateQueries({ queryKey: keys.review(id), exact: true });
      qc.invalidateQueries({ queryKey: ["review", id, "findings"] });
      qc.invalidateQueries({ queryKey: keys.files(id) });
      qc.invalidateQueries({ queryKey: keys.dashboard });
      qc.invalidateQueries({ queryKey: ["reviews"] });
    }
    previous.current = terminal;
  }, [terminal, id, qc]);
  return query;
}

export function useCancelReview(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.cancelReview(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["review", id] });
      toast.success("Review cancelled");
    },
    onError: (e: ApiError) => toast.error(e.message),
  });
}

export const useFindings = (id: string, enabled = true) =>
  useQuery({ queryKey: keys.findings(id), queryFn: () => api.findings(id), enabled });
export const useReviewFiles = (id: string, enabled = true) =>
  useQuery({ queryKey: keys.files(id), queryFn: () => api.reviewFiles(id), enabled });
export const useFileContent = (id: string, path: string | null) =>
  useQuery({
    queryKey: keys.file(id, path ?? ""),
    queryFn: () => api.fileContent(id, path!),
    enabled: !!path,
    staleTime: Infinity,
  });
export const useComparison = (id: string, base?: string) =>
  useQuery({ queryKey: keys.compare(id, base), queryFn: () => api.compare(id, base), retry: false });

export function useUpdateFinding(reviewId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: FindingStatus }) => api.updateFinding(id, status),
    onSuccess: (finding) => {
      qc.invalidateQueries({ queryKey: ["review", reviewId, "findings"] });
      qc.invalidateQueries({ queryKey: keys.dashboard });
      toast.success(`Finding marked ${finding.status.toLowerCase()}`);
    },
    onError: (e: ApiError) => toast.error(e.message),
  });
}

export function usePublishComments(reviewId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (findingIds: string[]) => api.publishComments(reviewId, findingIds),
    onSuccess: (result) => {
      qc.invalidateQueries({ queryKey: ["review", reviewId, "findings"] });
      toast.success(`Published ${result.published} comment${result.published === 1 ? "" : "s"} to GitHub`);
    },
    onError: (e: ApiError) => toast.error(e.message),
  });
}

export const useDashboardSummary = () => useQuery({ queryKey: [...keys.dashboard, "summary"], queryFn: api.dashboardSummary });
export const useActivity = (days = 30) => useQuery({ queryKey: [...keys.dashboard, "activity", days], queryFn: () => api.activity(days) });
export const useDistribution = () => useQuery({ queryKey: [...keys.dashboard, "distribution"], queryFn: api.distribution });

export const useSettings = () => useQuery({ queryKey: keys.settings, queryFn: api.settings });

export function useUpdateSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateSettings) => api.updateSettings(body),
    onSuccess: (s) => {
      qc.setQueryData(keys.settings, s);
      toast.success("Settings saved");
    },
  });
}

export function useApiKey() {
  const qc = useQueryClient();
  const set = useMutation({
    mutationFn: (key: string) => api.setApiKey(key),
    onSuccess: (s) => {
      qc.setQueryData(keys.settings, s);
      toast.success("API key saved");
    },
    onError: (e: ApiError) => toast.error(e.message),
  });
  const clear = useMutation({
    mutationFn: () => api.clearApiKey(),
    onSuccess: (s) => {
      qc.setQueryData(keys.settings, s);
      toast.success("API key removed");
    },
    onError: (e: ApiError) => toast.error(e.message),
  });
  return { set, clear };
}
