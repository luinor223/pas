import { createFileRoute } from "@tanstack/react-router";
import { RolePermissionEditor } from "@/features/administration/role-permissions";

export const Route = createFileRoute("/administration/roles")({ component: () => <RolePermissionEditor /> });
