import { createRootRoute, Outlet, redirect } from "@tanstack/react-router";
import { AppShell } from "@/components/layout/AppShell";
import { useAuthStore } from "@/stores/auth.store";

export const Route = createRootRoute({
  beforeLoad: ({ location }) => {
    const { isAuthenticated } = useAuthStore.getState();
    const isLogin = location.pathname === "/login";
    if (!isAuthenticated && !isLogin) {
      throw redirect({ to: "/login" });
    }
    if (isAuthenticated && isLogin) {
      throw redirect({ to: "/" });
    }
  },
  component: Root,
});

function Root() {
  const isAuth = useAuthStore((s) => s.isAuthenticated);
  const pathname = typeof window !== "undefined" ? window.location.pathname : "";
  const isLoginPage = pathname === "/login";
  // TanStack will handle redirect via beforeLoad; but for shell rendering:
  if (!isAuth && isLoginPage) return <Outlet />;
  if (!isAuth) return <Outlet />;
  if (isLoginPage) return <Outlet />;
  return (
    <AppShell>
      <Outlet />
    </AppShell>
  );
}
