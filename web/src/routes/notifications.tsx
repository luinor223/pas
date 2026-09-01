import { createFileRoute } from "@tanstack/react-router";
import { Placeholder } from "@/shared/components/Placeholder";

export const Route = createFileRoute("/notifications")({
  component: () => <Placeholder title="Notifications" note="System and workflow notifications." />,
});
