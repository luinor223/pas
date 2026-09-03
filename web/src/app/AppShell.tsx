import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import {
  LayoutDashboard, Building2, FileText, FilePlus2, Tags, BarChart3, ReceiptText,
  CheckSquare, PenLine, Bell, ScrollText, Settings, LogOut, ChevronRight,
  PanelLeftClose, PanelLeftOpen, UserRound, ChevronDown,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Logo, LogoMark } from "@/shared/components/Logo";
import { useCurrentUser } from "@/features/auth/hooks/useCurrentUser";
import { usePermissions } from "@/features/auth/hooks/usePermissions";
import { authApi } from "@/features/auth/services/authApi";
import { useQuery } from "@tanstack/react-query";
import { unreadCountQuery } from "@/features/notification/hooks/notificationQueries";
import { departmentLabel } from "@/shared/lib/labels";

type Item = { to: string; label: string; icon: React.ReactNode; permission?: string };
type Group = { heading: string; items: Item[] };

const NAV: Group[] = [
  { heading: "Overview", items: [
    { to: "/", label: "Dashboard", icon: <LayoutDashboard size={17} /> },
  ]},
  { heading: "Business Records", items: [
    { to: "/customers", label: "Customers", icon: <Building2 size={17} /> },
    { to: "/contracts", label: "Contracts", icon: <FileText size={17} /> },
    { to: "/addenda", label: "Addenda", icon: <FilePlus2 size={17} /> },
    { to: "/price-lists", label: "Price Lists", icon: <Tags size={17} /> },
    { to: "/volume-records", label: "Volume Records", icon: <BarChart3 size={17} /> },
    { to: "/payment-statements", label: "Payment Statements", icon: <ReceiptText size={17} /> },
  ]},
  { heading: "Workflow", items: [
    { to: "/approvals", label: "Approvals", icon: <CheckSquare size={17} /> },
    { to: "/e-signatures", label: "E-Signatures", icon: <PenLine size={17} /> },
    { to: "/notifications", label: "Notifications", icon: <Bell size={17} /> },
  ]},
  { heading: "System", items: [
    { to: "/audit-log", label: "Audit Log", icon: <ScrollText size={17} />, permission: "audit:view_all" },
    { to: "/admin/users", label: "Administration", icon: <Settings size={17} />, permission: "user:manage" },
  ]},
];

const CRUMB: Record<string, { group: string; label: string }> = Object.fromEntries(
  NAV.flatMap((g) => g.items.map((i) => [i.to, { group: g.heading, label: i.label }]))
);
CRUMB["/profile"] = { group: "Overview", label: "Profile" };

export function AppShell({ children }: { children: React.ReactNode }) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => window.localStorage.getItem("pas.sidebar-collapsed") === "true"
  );
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const accountMenuRef = useRef<HTMLDivElement>(null);
  const { data: user } = useCurrentUser();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const perms = usePermissions();
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  // Counters are computed unfiltered, so a one-row page is enough for the badge.
  const unread = useQuery({ ...unreadCountQuery(), enabled: perms.includes("notification:read") }).data ?? 0;

  const crumb = CRUMB[pathname] ?? matchCrumb(pathname);
  const initials = (user?.fullName ?? "?").split(" ").map((n) => n[0]).slice(0, 2).join("").toUpperCase();

  useEffect(() => {
    if (!accountMenuOpen) return;

    const closeOnOutsideClick = (event: MouseEvent) => {
      if (!accountMenuRef.current?.contains(event.target as Node)) setAccountMenuOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setAccountMenuOpen(false);
    };

    document.addEventListener("mousedown", closeOnOutsideClick);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("mousedown", closeOnOutsideClick);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [accountMenuOpen]);

  async function onLogout() {
    try { await authApi.logout(); } catch { /* ignore */ }
    queryClient.clear();
    navigate({ to: "/login" });
  }

  const isActive = (to: string) => {
    if (to === "/") return pathname === "/";
    if (to.startsWith("/admin")) return pathname.startsWith("/admin");
    return pathname === to || pathname.startsWith(to + "/");
  };

  function toggleSidebar() {
    setSidebarCollapsed((collapsed) => {
      window.localStorage.setItem("pas.sidebar-collapsed", String(!collapsed));
      return !collapsed;
    });
  }

  return (
    <div className="flex h-screen overflow-hidden">
      {/* Sidebar */}
      <aside
        className={`relative flex shrink-0 flex-col bg-navy text-navy-foreground transition-[width] duration-200 ${
          sidebarCollapsed ? "w-16" : "w-60"
        }`}
      >
        <div className={`flex h-16 items-center ${sidebarCollapsed ? "justify-center px-2" : "px-5"}`}>
          {sidebarCollapsed ? <LogoMark tone="light" className="h-9 w-9" /> : <Logo tone="light" />}
        </div>
        <button
          type="button"
          onClick={toggleSidebar}
          title={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
          aria-label={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
          className="absolute -right-3 top-5 z-20 flex h-7 w-7 items-center justify-center rounded-full border border-border bg-card text-muted-foreground shadow-sm hover:bg-muted hover:text-foreground"
        >
          {sidebarCollapsed ? <PanelLeftOpen size={15} /> : <PanelLeftClose size={15} />}
        </button>
        <nav className={`flex-1 overflow-y-auto scroll-thin py-3 ${sidebarCollapsed ? "space-y-2 px-2" : "space-y-3 px-3"}`}>
          {NAV.map((group) => (
            <div key={group.heading}>
              {sidebarCollapsed ? (
                <div className="mx-2 mb-1 border-t border-white/10" />
              ) : (
                <div className="px-3 pb-1 text-[10px] font-semibold uppercase tracking-[0.12em] text-white/40">
                  {group.heading}
                </div>
              )}
              <div className="space-y-0.5">
                {group.items
                  .filter((i) => !i.permission || perms.includes(i.permission))
                  .map((i) => {
                    const active = isActive(i.to);
                    return (
                      <Link
                        key={i.to}
                        to={i.to}
                        title={sidebarCollapsed ? i.label : undefined}
                        className={
                          `flex items-center rounded-lg py-1.5 text-sm font-medium transition-colors ${sidebarCollapsed ? "justify-center px-2" : "gap-3 px-3"} ` +
                          (active ? "bg-white/12 text-white" : "text-white/70 hover:bg-white/8 hover:text-white")
                        }
                      >
                        <span className={`shrink-0 ${active ? "text-white" : "text-white/60"}`}>{i.icon}</span>
                        {!sidebarCollapsed && i.label}
                      </Link>
                    );
                  })}
              </div>
            </div>
          ))}
        </nav>
      </aside>

      {/* Main */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-16 shrink-0 items-center gap-4 border-b border-border bg-card px-6">
          <div className="flex items-center gap-1.5 text-sm">
            <span className="text-muted-foreground">{crumb.group}</span>
            <ChevronRight size={15} className="text-muted-foreground/50" />
            <span className="font-semibold text-foreground">{crumb.label}</span>
          </div>
          <div className="ml-auto flex items-center gap-3">
            <Link
              to="/notifications"
              className="relative rounded-lg p-2 text-muted-foreground hover:bg-muted"
              title={unread > 0 ? `${unread} unread notifications` : "Notifications"}
            >
              <Bell size={18} />
              {unread > 0 && (
                <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold tabular-nums text-white">
                  {unread > 99 ? "99+" : unread}
                </span>
              )}
            </Link>
            <div ref={accountMenuRef} className="relative">
              <button
                type="button"
                aria-label="Open account menu"
                aria-haspopup="menu"
                aria-expanded={accountMenuOpen}
                onClick={() => setAccountMenuOpen((open) => !open)}
                className="flex items-center gap-2 rounded-full p-1 pr-2 hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <span className="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">
                  {initials}
                </span>
                <ChevronDown size={14} className="text-muted-foreground" aria-hidden="true" />
              </button>

              {accountMenuOpen && (
                <div
                  role="menu"
                  className="absolute right-0 top-full z-50 mt-2 w-64 overflow-hidden rounded-lg border border-border bg-card shadow-lg"
                >
                  <div className="border-b border-border px-4 py-3">
                    <div className="truncate text-sm font-semibold">{user?.fullName ?? "Your account"}</div>
                    <div className="mt-0.5 truncate text-xs text-muted-foreground">
                      {user?.department ? departmentLabel(user.department) : user?.username}
                    </div>
                  </div>
                  <div className="p-1.5">
                    <Link
                      to="/profile"
                      role="menuitem"
                      onClick={() => setAccountMenuOpen(false)}
                      className="flex items-center gap-2.5 rounded-md px-3 py-2 text-sm hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    >
                      <UserRound size={16} className="text-muted-foreground" />
                      View profile
                    </Link>
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => { setAccountMenuOpen(false); void onLogout(); }}
                      className="flex w-full items-center gap-2.5 rounded-md px-3 py-2 text-left text-sm hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    >
                      <LogOut size={16} className="text-muted-foreground" />
                      Sign out
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </header>
        <main className="min-w-0 flex-1 overflow-x-hidden overflow-y-auto scroll-thin">
          <div className="min-w-0 p-4 sm:p-6">{children}</div>
        </main>
      </div>
    </div>
  );
}

function matchCrumb(pathname: string): { group: string; label: string } {
  const hit = Object.entries(CRUMB).find(([to]) => to !== "/" && pathname.startsWith(to));
  return hit ? hit[1] : { group: "Overview", label: "Dashboard" };
}
