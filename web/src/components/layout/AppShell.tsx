import { Link, useNavigate } from "@tanstack/react-router";
import { Button } from "@/shared/components/ui/button";
import { useAuthStore } from "@/stores/auth.store";
import { usePermissions } from "@/shared/hooks/usePermission";
import { api } from "@/api/client";
import { LogOut, LayoutDashboard, Users, Shield, FileText, Bell } from "lucide-react";

type NavItem = { to: string; label: string; icon: React.ReactNode; permission?: string; exact?: boolean };

const nav: NavItem[] = [
  { to: "/", label: "Dashboard", icon: <LayoutDashboard size={18} />, exact: true },
  { to: "/administration/users", label: "Users", icon: <Users size={18} />, permission: "user:manage" },
  { to: "/administration/roles", label: "Roles & Permissions", icon: <Shield size={18} />, permission: "user:manage" },
  { to: "/administration/workflows", label: "Workflows (stub)", icon: <FileText size={18} />, permission: "workflow:configure" },
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const user = useAuthStore((s) => s.user);
  const clear = useAuthStore((s) => s.clear);
  const navigate = useNavigate();
  const perms = usePermissions();
  const has = (code: string) => perms.includes(code);

  async function onLogout() {
    const rt = useAuthStore.getState().refreshToken;
    try {
      if (rt) await api.post("/auth/logout", { refreshToken: rt });
    } catch {
      // ignore
    }
    clear();
    navigate({ to: "/login" });
  }

  return (
    <div className="min-h-screen bg-muted/30">
      <header className="h-14 border-b bg-white flex items-center justify-between px-4 sticky top-0 z-40">
        <div className="flex items-center gap-6">
          <Link to="/" className="font-bold text-primary tracking-tight">PAS</Link>
          <span className="text-xs text-muted-foreground hidden sm:inline">Business Document Management</span>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-sm hidden md:block">
            <span className="font-medium">{user?.fullName}</span>
            <span className="text-muted-foreground"> · {user?.department} · {user?.roles.join(", ")}</span>
          </span>
          <Button variant="ghost" size="icon" title="Notifications" onClick={() => navigate({ to: "/" })}>
            <Bell size={18} />
          </Button>
          <Button variant="outline" size="sm" onClick={onLogout}>
            <LogOut size={14} className="mr-2" /> Logout
          </Button>
        </div>
      </header>

      <div className="flex">
        <aside className="w-64 border-r bg-white min-h-[calc(100vh-56px)] p-3 hidden md:block">
          <nav className="space-y-1">
            {nav
              .filter((n) => !n.permission || has(n.permission))
              .map((n) => (
                <Link
                  key={n.to}
                  to={n.to}
                  className="flex items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-muted [&.active]:bg-primary [&.active]:text-white"
                >
                  {n.icon} {n.label}
                </Link>
              ))}
            {/* future: hide Audit Log unless audit:view_all */}
          </nav>
          <div className="mt-auto pt-6">
            {user && (
              <div className="flex items-center gap-3 px-3 py-3 rounded-lg bg-muted/50 border">
                <div className="h-9 w-9 rounded-full bg-blue-600 text-white flex items-center justify-center text-sm font-semibold shrink-0">
                  {user.fullName
                    .split(" ")
                    .map((n) => n[0])
                    .slice(0, 2)
                    .join("")
                    .toUpperCase()}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium leading-none truncate">{user.fullName}</div>
                  <div className="text-xs text-muted-foreground truncate">{user.roles[0]?.replace("_", " ") ?? user.roles.join(", ")}</div>
                </div>
              </div>
            )}
          </div>
        </aside>

        <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">{children}</main>
      </div>
    </div>
  );
}
