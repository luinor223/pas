import { createFileRoute } from "@tanstack/react-router";
import { ApprovalInbox } from "@/features/approval";

export const Route = createFileRoute("/approvals")({
  validateSearch: (search: Record<string, unknown>) => ({
    tab: search.tab === "SUBMITTED" || search.tab === "COMPLETED" ? search.tab : undefined,
    q: typeof search.q === "string" && search.q ? search.q : undefined,
    documentType: typeof search.documentType === "string" && search.documentType ? search.documentType : undefined,
    priority: typeof search.priority === "string" && search.priority ? search.priority : undefined,
    page: typeof search.page === "number" && Number.isInteger(search.page) && search.page > 0 ? search.page : undefined,
  }),
  component: ApprovalInbox,
});
