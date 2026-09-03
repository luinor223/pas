import { createFileRoute } from "@tanstack/react-router";
import { NotificationList } from "@/features/notification/components/NotificationList";

export const Route = createFileRoute("/notifications")({
  component: NotificationList,
});
