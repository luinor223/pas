import { createFileRoute } from "@tanstack/react-router";
import { UserTable } from "@/features/administration/user-list";

export const Route = createFileRoute("/administration/users")({ component: UserPage });
function UserPage() {
  return <UserTable />;
}
