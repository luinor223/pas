import { createFileRoute, Link, Outlet } from "@tanstack/react-router";
import { usePermissions } from "@/features/auth/hooks/usePermissions";
import { useCurrentUser } from "@/features/auth/hooks/useCurrentUser";
import { Forbidden } from "@/shared/components/Forbidden";

export const Route = createFileRoute("/admin")({ component: AdminLayout });

function AdminLayout() {
  const { isLoading } = useCurrentUser();
  const perms = usePermissions();
  if (isLoading) {
    return <div className="text-sm text-muted-foreground p-4">Loading...</div>;
  }
  if (!perms.includes("user:manage")) {
    return <Forbidden message="You do not have access to Administration." />;
  }
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-bold">Administration</h1>
        <p className="text-sm text-muted-foreground">Manage users, roles, permissions, workflows and document types.</p>
      </div>
      <div className="flex gap-2 border-b pb-2">
        <Link to="/admin/users" className="text-sm px-3 py-1 rounded hover:bg-muted [&.active]:bg-primary [&.active]:text-white">Users</Link>
        <Link to="/admin/roles" className="text-sm px-3 py-1 rounded hover:bg-muted [&.active]:bg-primary [&.active]:text-white">Roles & Permissions</Link>
        <Link to="/admin/workflows" className="text-sm px-3 py-1 rounded hover:bg-muted [&.active]:bg-primary [&.active]:text-white">Workflows</Link>
        <Link to="/admin/document-types" className="text-sm px-3 py-1 rounded hover:bg-muted [&.active]:bg-primary [&.active]:text-white">Document Types</Link>
      </div>
      <Outlet />
    </div>
  );
}
