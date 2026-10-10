import { useQueryClient } from "@tanstack/react-query";
import { lazy, Suspense, useEffect } from "react";
import { Route, Routes, useNavigate } from "react-router";
import { toast } from "sonner";

import { onAuthFailure } from "./api/client";
import { keys } from "./api/hooks";
import { AppShell } from "./components/AppShell";
import { RequireAuth } from "./components/RequireAuth";
import { LoadingBlock } from "./components/ui";
import Landing from "./pages/Landing";
import Login from "./pages/Login";
import NotFound from "./pages/NotFound";

const Dashboard = lazy(() => import("./pages/Dashboard"));
const Repositories = lazy(() => import("./pages/Repositories"));
const RepositoryDetail = lazy(() => import("./pages/RepositoryDetail"));
const ReviewWorkspace = lazy(() => import("./pages/ReviewWorkspace"));
const ReviewHistory = lazy(() => import("./pages/ReviewHistory"));
const ReviewCompare = lazy(() => import("./pages/ReviewCompare"));
const Settings = lazy(() => import("./pages/Settings"));

/** Reacts to 401s from any request: expired session -> login page; revoked GitHub grant -> re-auth prompt. */
function SessionWatcher() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  useEffect(
    () =>
      onAuthFailure((error) => {
        if (error.code === "GITHUB_REAUTH_REQUIRED") {
          toast.error("GitHub authorization expired or was revoked.", {
            id: "github-reauth",
            action: { label: "Sign in again", onClick: () => window.location.assign("/api/auth/github") },
          });
          return;
        }
        const wasSignedIn = !!qc.getQueryData(keys.me);
        qc.setQueryData(keys.me, null);
        if (wasSignedIn) {
          toast.message("Your session expired. Please sign in again.", { id: "session-expired" });
          navigate(`/login?expired=1&next=${encodeURIComponent(window.location.pathname)}`, { replace: true });
        }
      }),
    [qc, navigate],
  );
  return null;
}

export default function App() {
  return (
    <>
      <SessionWatcher />
      <Suspense fallback={<LoadingBlock rows={6} />}>
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/login" element={<Login />} />
          <Route element={<RequireAuth />}>
            <Route element={<AppShell />}>
              <Route path="/dashboard" element={<Dashboard />} />
              <Route path="/repositories" element={<Repositories />} />
              <Route path="/repositories/:repositoryId" element={<RepositoryDetail />} />
              <Route path="/reviews" element={<ReviewHistory />} />
              <Route path="/reviews/:reviewId" element={<ReviewWorkspace />} />
              <Route path="/reviews/:reviewId/compare" element={<ReviewCompare />} />
              <Route path="/settings" element={<Settings />} />
              <Route path="*" element={<NotFound />} />
            </Route>
          </Route>
        </Routes>
      </Suspense>
    </>
  );
}
