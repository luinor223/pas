import { createFileRoute, Link, Outlet } from "@tanstack/react-router";

export const Route = createFileRoute("/administration")({ component: AdminLayout });

function AdminLayout() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-bold">Administration</h1>
        <p className="text-sm text-muted-foreground">System — Users, Roles & Permissions (16-administration.png). Workflows & Document Types are stubs for future services.</p>
      </div>
      <div className="flex gap-2 border-b pb-2">
        <Link to="/administration/users" className="text-sm px-3 py-1 rounded hover:bg-muted [&.active]:bg-primary [&.active]:text-white">Users</Link>
        <Link to="/administration/roles" className="text-sm px-3 py-1 rounded hover:bg-muted [&.active]:bg-primary [&.active]:text-white">Roles & Permissions</Link>
        <Link to="/administration/workflows" className="text-sm px-3 py-1 rounded hover:bg-muted [&.active]:bg-primary [&.active]:text-white">Workflows</Link>
        <Link to="/administration/document-types" className="text-sm px-3 py-1 rounded hover:bg-muted [&.active]:bg-primary [&.active]:text-white">Document Types</Link>
      </div>
      <Outlet />
    </div>
  );
}
