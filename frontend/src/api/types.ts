export type Severity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO";
export type FindingCategory =
  | "BUG"
  | "SECURITY"
  | "PERFORMANCE"
  | "RELIABILITY"
  | "MAINTAINABILITY"
  | "CODE_QUALITY"
  | "TESTING"
  | "BEST_PRACTICE";
export type FindingSource = "AI" | "STATIC_ANALYSIS";
export type Verification = "UNVERIFIED" | "STATIC_ANALYZER_REPORTED" | "CONFIRMED_BY_TEST";
export type FindingStatus = "OPEN" | "RESOLVED" | "DISMISSED";
export type ReviewStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
export type ReviewType = "REPOSITORY" | "PULL_REQUEST";
export type AiStatus = "COMPLETED" | "PARTIAL" | "SKIPPED_NOT_CONFIGURED" | "SKIPPED_DISABLED" | "FAILED";

export const SEVERITIES: Severity[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"];
export const CATEGORIES: FindingCategory[] = [
  "BUG",
  "SECURITY",
  "PERFORMANCE",
  "RELIABILITY",
  "MAINTAINABILITY",
  "CODE_QUALITY",
  "TESTING",
  "BEST_PRACTICE",
];

export interface AuthConfig {
  githubConfigured: boolean;
  aiConfigured: boolean;
  aiProvider: string;
  webhooksConfigured: boolean;
}

export interface Me {
  id: string;
  login: string;
  name: string | null;
  email: string | null;
  avatarUrl: string | null;
  githubConnected: boolean;
  githubScopes: string | null;
  connectedAt: string | null;
}

export interface Repository {
  id: string;
  githubId: number;
  fullName: string;
  owner: string;
  name: string;
  description: string | null;
  language: string | null;
  isPrivate: boolean;
  defaultBranch: string | null;
  htmlUrl: string | null;
  autoReview: boolean;
  pushedAt: string | null;
  lastSyncedAt: string;
}

export interface Branch {
  name: string;
  sha: string;
  isProtected: boolean;
}

export interface Commit {
  sha: string;
  message: string;
  author: string | null;
  date: string | null;
  htmlUrl: string | null;
}

export interface PullRequest {
  number: number;
  title: string;
  state: string;
  author: string | null;
  headRef: string | null;
  headSha: string | null;
  baseRef: string | null;
  htmlUrl: string | null;
  updatedAt: string | null;
  draft: boolean;
}

export interface SeverityCounts {
  critical: number;
  high: number;
  medium: number;
  low: number;
  info: number;
}

export interface ReviewSummary {
  id: string;
  repositoryId: string;
  repositoryFullName: string | null;
  type: ReviewType;
  branch: string | null;
  commitSha: string | null;
  pullRequestNumber: number | null;
  pullRequestTitle: string | null;
  status: ReviewStatus;
  triggerSource: "MANUAL" | "WEBHOOK";
  score: number | null;
  aiStatus: AiStatus | null;
  counts: SeverityCounts;
  totalFindings: number;
  createdAt: string;
  completedAt: string | null;
}

export interface ReviewDetail {
  review: ReviewSummary;
  summary: string | null;
  notes: string[];
  errorMessage: string | null;
  aiProvider: string | null;
  aiModel: string | null;
  filesConsidered: number;
  filesAnalyzed: number;
  filesSkipped: number;
  startedAt: string | null;
  repositoryHtmlUrl: string | null;
  pullRequestHtmlUrl: string | null;
  scoreFormula: string;
}

export interface ReviewStatusInfo {
  id: string;
  status: ReviewStatus;
  aiStatus: AiStatus | null;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
  terminal: boolean;
}

export interface Finding {
  id: string;
  reviewId: string;
  source: FindingSource;
  analyzer: string;
  verification: Verification;
  category: FindingCategory;
  severity: Severity;
  confidence: number;
  title: string;
  filePath: string;
  startLine: number | null;
  endLine: number | null;
  description: string;
  impact: string | null;
  recommendation: string | null;
  suggestedCode: string | null;
  originalCode: string | null;
  ruleId: string | null;
  status: FindingStatus;
  inDiff: boolean;
  publishedAt: string | null;
  createdAt: string;
}

export interface ReviewFile {
  path: string;
  language: string | null;
  analyzed: boolean;
  skipReason: string | null;
  changedLines: string | null;
  findingCount: number;
}

export interface FileContent {
  path: string;
  language: string;
  content: string;
  commitSha: string;
}

export interface Comparison {
  baseReviewId: string;
  currentReviewId: string;
  baseCreatedAt: string;
  newFindings: Finding[];
  fixedFindings: Finding[];
  recurringFindings: Finding[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface DashboardSummary {
  repositoriesAccessible: number;
  repositoriesReviewed: number;
  totalReviews: number;
  completedReviews: number;
  activeReviews: number;
  averageScore: number | null;
  openCritical: number;
  openHigh: number;
  openFindings: number;
  resolvedFindings: number;
  dismissedFindings: number;
  recentReviews: ReviewSummary[];
}

export interface ActivityPoint {
  date: string;
  reviews: number;
}

export interface CategoryCount {
  category: FindingCategory;
  count: number;
}

export interface Option {
  id: string;
  label: string;
  available: boolean;
}

export interface ProviderOption {
  id: string;
  label: string;
  defaultModel: string;
  suggestedModels: string[];
}

export interface Settings {
  aiProvider: string;
  aiModel: string | null;
  aiKeyConfigured: boolean;
  aiKeyHint: string | null;
  aiKeySource: "user" | "server" | "none";
  serverAiConfigured: boolean;
  serverAiProvider: string;
  providers: ProviderOption[];
  enabledAnalyzers: string[];
  availableAnalyzers: Option[];
  enabledLanguages: string[];
  availableLanguages: Option[];
  minSeverity: Severity;
  maxFiles: number;
  maxFilesLimit: number;
  dataRetention: string;
}

export interface UpdateSettings {
  aiProvider: string;
  aiModel: string | null;
  enabledAnalyzers: string[];
  enabledLanguages: string[];
  minSeverity: Severity;
  maxFiles: number;
}

export interface CreateReviewRequest {
  repositoryId: string;
  type: ReviewType;
  branch?: string;
  pullRequestNumber?: number;
}

export interface PublishResult {
  published: number;
  skipped: number;
  githubReviewUrl: string | null;
}
