import { Link } from "react-router";

export default function NotFound() {
  return (
    <div className="grid min-h-[60vh] place-items-center px-5">
      <div className="max-w-md">
        <p className="font-mono text-sm text-sage">404</p>
        <h1 className="mt-2 text-3xl font-bold">This page does not exist</h1>
        <p className="mt-3 text-muted">The link may be outdated, or the review belongs to another account.</p>
        <Link to="/dashboard" className="mt-6 inline-block text-sage hover:underline">
          Go to the dashboard
        </Link>
      </div>
    </div>
  );
}
