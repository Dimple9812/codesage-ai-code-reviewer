import { Navigate, Outlet, useLocation } from "react-router";

import { useMe } from "../api/hooks";
import { ErrorState, LoadingBlock } from "./ui";

export function RequireAuth() {
  const { data: me, isLoading, error, refetch } = useMe();
  const location = useLocation();
  if (isLoading) {
    return (
      <div className="mx-auto max-w-md p-10">
        <LoadingBlock label="Checking your session" />
      </div>
    );
  }
  if (error) {
    return (
      <div className="mx-auto max-w-lg p-10">
        <ErrorState error={error} onRetry={() => refetch()} />
      </div>
    );
  }
  if (!me) {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;
  }
  return <Outlet />;
}
