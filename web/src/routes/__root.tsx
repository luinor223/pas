import { createRootRouteWithContext, Outlet, redirect } from "@tanstack/react-router";
import type { QueryClient } from "@tanstack/react-query";
import { AppShell } from "@/app/AppShell";
import { currentUserQuery, useCurrentUser } from "@/features/auth/hooks/useCurrentUser";

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  beforeLoad: async ({ location, context }) => {
    const isLogin = location.pathname === "/login";
    let authed = false;
    try {
      await context.queryClient.ensureQueryData(currentUserQuery);
      authed = true;
    } catch {
      authed = false;
    }
    if (!authed && !isLogin) throw redirect({ to: "/login" });
    if (authed && isLogin) throw redirect({ to: "/" });
  },
  component: Root,
});

function Root() {
  const { data: user } = useCurrentUser();
  if (!user) return <Outlet />;
  return (
    <AppShell>
      <Outlet />
    </AppShell>
  );
}
