import clsx from "clsx";
import { FolderGit2, History, LayoutDashboard, LogOut, Menu, Settings, X } from "lucide-react";
import { useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "../api/endpoints";
import { keys, useMe } from "../api/hooks";
import { Logo } from "./Logo";

const NAV = [
  { to: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { to: "/repositories", label: "Repositories", icon: FolderGit2 },
  { to: "/reviews", label: "Review history", icon: History },
  { to: "/settings", label: "Settings", icon: Settings },
];

export function AppShell() {
  const { data: me } = useMe();
  const [open, setOpen] = useState(false);
  const qc = useQueryClient();
  const navigate = useNavigate();

  async function signOut() {
    try {
      await api.logout();
    } finally {
      qc.setQueryData(keys.me, null);
      qc.clear();
      toast.success("Signed out");
      navigate("/");
    }
  }

  const nav = (
    <nav aria-label="Main" className="flex flex-col gap-1">
      {NAV.map(({ to, label, icon: Icon }) => (
        <NavLink
          key={to}
          to={to}
          onClick={() => setOpen(false)}
          className={({ isActive }) =>
            clsx(
              "flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors",
              isActive ? "bg-raised text-ink" : "text-muted hover:bg-raised/60 hover:text-ink",
            )
          }
        >
          {({ isActive }) => (
            <>
              <Icon className={clsx("size-4", isActive && "text-sage")} aria-hidden />
              {label}
            </>
          )}
        </NavLink>
      ))}
    </nav>
  );

  const account = me && (
    <div className="flex items-center gap-3 border-t border-line pt-4">
      {me.avatarUrl ? (
        <img src={me.avatarUrl} alt="" className="size-8 rounded-full" />
      ) : (
        <div className="grid size-8 place-items-center rounded-full bg-raised text-sm">{me.login[0]?.toUpperCase()}</div>
      )}
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{me.name ?? me.login}</p>
        <p className="truncate text-xs text-muted">@{me.login}</p>
      </div>
      <button onClick={signOut} className="rounded p-1.5 text-muted hover:bg-raised hover:text-ink" aria-label="Sign out" title="Sign out">
        <LogOut className="size-4" />
      </button>
    </div>
  );

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[15rem_1fr]">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-sage focus:px-3 focus:py-2 focus:text-night">
        Skip to content
      </a>
      <aside className="hidden h-dvh flex-col justify-between border-r border-line bg-surface p-4 lg:sticky lg:top-0 lg:flex">
        <div className="space-y-8">
          <Logo className="px-2 text-lg" />
          {nav}
        </div>
        {account}
      </aside>

      <div className="flex items-center justify-between border-b border-line bg-surface px-4 py-3 lg:hidden">
        <Logo />
        <button onClick={() => setOpen((o) => !o)} aria-label={open ? "Close menu" : "Open menu"} aria-expanded={open} className="rounded p-2 hover:bg-raised">
          {open ? <X className="size-5" /> : <Menu className="size-5" />}
        </button>
      </div>
      {open && (
        <div className="space-y-6 border-b border-line bg-surface p-4 lg:hidden">
          {nav}
          {account}
        </div>
      )}

      <main id="main" className="min-w-0 px-4 py-6 sm:px-8 lg:py-8">
        <Outlet />
      </main>
    </div>
  );
}
