import { createFileRoute } from "@tanstack/react-router";
import { UserTable } from "@/features/admin";

export const Route = createFileRoute("/admin/users")({ component: UserPage });
function UserPage() {
  return <UserTable />;
}
