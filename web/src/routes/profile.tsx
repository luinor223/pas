import { createFileRoute } from "@tanstack/react-router";
import { UserProfile } from "@/features/auth/components/UserProfile";

export const Route = createFileRoute("/profile")({
  component: UserProfile,
});
