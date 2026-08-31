import { createFileRoute, Link } from "@tanstack/react-router";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/ui/card";
import { useAuthStore } from "@/stores/auth.store";
import { Button } from "@/shared/components/ui/button";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/api/client";

export const Route = createFileRoute("/")({ component: Dashboard });

function Dashboard() {
  const user = useAuthStore((s) => s.user);
  const usersQ = useQuery({ queryKey: ["users"], queryFn: async () => (await api.get("/users")).data, retry: false });
  const rolesQ = useQuery({ queryKey: ["roles"], queryFn: async () => (await api.get("/roles")).data, retry: false });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Dashboard</h1>
        <p className="text-muted-foreground text-sm">Welcome, {user?.fullName} — {user?.department} — {user?.roles.join(", ")}</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card><CardHeader><CardTitle className="text-base">Users</CardTitle></CardHeader><CardContent><div className="text-3xl font-bold">{Array.isArray(usersQ.data) ? usersQ.data.length : "-"}</div><div className="text-xs text-muted-foreground">total users (GET /users)</div></CardContent></Card>
        <Card><CardHeader><CardTitle className="text-base">Roles</CardTitle></CardHeader><CardContent><div className="text-3xl font-bold">{Array.isArray(rolesQ.data) ? rolesQ.data.length : "-"}</div><div className="text-xs text-muted-foreground">from V2 seed (7 roles)</div></CardContent></Card>
        <Card><CardHeader><CardTitle className="text-base">Docs</CardTitle></CardHeader><CardContent className="flex flex-col gap-2">
          <a className="text-sm text-primary underline" href="/docs/identity/swagger-ui/index.html" target="_blank" rel="noreferrer">Identity Swagger UI</a>
          <a className="text-sm text-primary underline" href="/docs/identity/v3/api-docs" target="_blank" rel="noreferrer">OpenAPI JSON</a>
          <a className="text-sm text-primary underline" href="/docs/workflow/swagger-ui/index.html" target="_blank" rel="noreferrer">Workflow Swagger</a>
        </CardContent></Card>
      </div>

      <Card>
        <CardHeader><CardTitle>Next steps</CardTitle></CardHeader>
        <CardContent className="flex gap-2">
          <Link to="/administration/users"><Button>Administration → Users</Button></Link>
          <Link to="/administration/roles"><Button variant="outline">Roles & Permissions</Button></Link>
        </CardContent>
      </Card>
    </div>
  );
}
