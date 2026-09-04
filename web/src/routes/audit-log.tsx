import { createFileRoute } from "@tanstack/react-router";
import { AuditRecordTable } from "@/features/audit/components/AuditRecordTable";

export const Route = createFileRoute("/audit-log")({
  validateSearch: (search: Record<string, unknown>) => ({
    sourceService: typeof search.sourceService === "string" && search.sourceService ? search.sourceService : undefined,
    entityType: typeof search.entityType === "string" && search.entityType ? search.entityType : undefined,
    q: typeof search.q === "string" && search.q ? search.q : undefined,
    action: typeof search.action === "string" && search.action ? search.action : undefined,
    from: typeof search.from === "string" && search.from ? search.from : undefined,
    to: typeof search.to === "string" && search.to ? search.to : undefined,
    page: typeof search.page === "number" && Number.isInteger(search.page) && search.page > 0 ? search.page : undefined,
  }),
  component: AuditRecordTable,
});
