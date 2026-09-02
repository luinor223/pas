import { createFileRoute } from "@tanstack/react-router";
import { RolePermissionEditor } from "@/features/admin";

export const Route = createFileRoute("/admin/roles")({ component: () => <RolePermissionEditor /> });
