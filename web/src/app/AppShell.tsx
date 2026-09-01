import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import {
  LayoutDashboard, Building2, FileText, FilePlus2, Tags, BarChart3, ReceiptText,
  CheckSquare, PenLine, Bell, ScrollText, Settings, LogOut, Search, Plus, ChevronRight,
} from "lucide-react";
import { Logo } from "@/shared/components/Logo";
import { useAuthStore } from "@/features/auth/store/authStore";
import { usePermissions } from "@/features/auth/hooks/usePermissions";
import { api } from "@/shared/api/client";

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
    { to: "/audit-log", label: "Audit Log", icon: <ScrollText size={17} /> },
    { to: "/admin/users", label: "Administration", icon: <Settings size={17} />, permission: "user:manage" },
  ]},
];

const CRUMB: Record<string, { group: string; label: string }> = Object.fromEntries(
  NAV.flatMap((g) => g.items.map((i) => [i.to, { group: g.heading, label: i.label }]))
);

export function AppShell({ children }: { children: React.ReactNode }) {
  const user = useAuthStore((s) => s.user);
  const clear = useAuthStore((s) => s.clear);
  const navigate = useNavigate();
  const perms = usePermissions();
  const pathname = useRouterState({ select: (s) => s.location.pathname });

  const crumb = CRUMB[pathname] ?? matchCrumb(pathname);
  const initials = (user?.fullName ?? "?").split(" ").map((n) => n[0]).slice(0, 2).join("").toUpperCase();

  async function onLogout() {
    const rt = useAuthStore.getState().refreshToken;
    try { if (rt) await api.post("/auth/logout", { refreshToken: rt }); } catch { /* ignore */ }
    clear();
    navigate({ to: "/login" });
  }

  const isActive = (to: string) => to === "/" ? pathname === "/" : pathname.startsWith(to.replace(/\/users$/, ""));

  return (
    <div className="flex h-screen overflow-hidden">
      {/* Sidebar */}
      <aside className="flex w-60 shrink-0 flex-col bg-navy text-navy-foreground">
        <div className="flex h-16 items-center px-5">
          <Logo tone="light" />
        </div>
        <nav className="flex-1 space-y-6 overflow-y-auto scroll-thin px-3 py-4">
          {NAV.map((group) => (
            <div key={group.heading}>
              <div className="px-3 pb-2 text-[10px] font-semibold uppercase tracking-[0.12em] text-white/40">
                {group.heading}
              </div>
              <div className="space-y-0.5">
                {group.items
                  .filter((i) => !i.permission || perms.includes(i.permission))
                  .map((i) => {
                    const active = isActive(i.to);
                    return (
                      <Link
                        key={i.to}
                        to={i.to}
                        className={
                          "flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors " +
                          (active ? "bg-white/12 text-white" : "text-white/70 hover:bg-white/8 hover:text-white")
                        }
                      >
                        <span className={active ? "text-white" : "text-white/60"}>{i.icon}</span>
                        {i.label}
                      </Link>
                    );
                  })}
              </div>
            </div>
          ))}
        </nav>
        <div className="flex items-center gap-3 border-t border-white/10 px-4 py-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-semibold text-white">
            {initials}
          </div>
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-medium text-white">{user?.fullName ?? "-"}</div>
            <div className="truncate text-xs text-white/50">{user?.department ?? ""}</div>
          </div>
          <button onClick={onLogout} title="Sign out" className="rounded-md p-1.5 text-white/60 hover:bg-white/10 hover:text-white">
            <LogOut size={16} />
          </button>
        </div>
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
            <div className="relative hidden md:block">
              <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
              <input
                placeholder="Search records..."
                className="h-9 w-64 rounded-lg border border-border bg-background pl-9 pr-3 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-primary/20"
              />
            </div>
            <button className="relative rounded-lg p-2 text-muted-foreground hover:bg-muted" title="Notifications">
              <Bell size={18} />
              <span className="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-destructive" />
            </button>
            <button className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3.5 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90">
              <Plus size={16} /> New Contract
            </button>
          </div>
        </header>
        <main className="flex-1 overflow-y-auto scroll-thin">
          <div className="p-6">{children}</div>
        </main>
      </div>
    </div>
  );
}

function matchCrumb(pathname: string): { group: string; label: string } {
  const hit = Object.entries(CRUMB).find(([to]) => to !== "/" && pathname.startsWith(to));
  return hit ? hit[1] : { group: "Overview", label: "Dashboard" };
}
