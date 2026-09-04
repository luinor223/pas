import { createFileRoute } from "@tanstack/react-router";
import { NotificationList } from "@/features/notification/components/NotificationList";

export const Route = createFileRoute("/notifications")({
  validateSearch: (search: Record<string, unknown>) => ({
    tab: ["unread", "APPROVAL", "ESIGN", "EXPIRY", "SYSTEM"].includes(String(search.tab))
      ? String(search.tab)
      : undefined,
    page: typeof search.page === "number" && Number.isInteger(search.page) && search.page > 0
      ? search.page
      : undefined,
  }),
  component: NotificationList,
});
