import { createFileRoute } from "@tanstack/react-router";
import { AuditRecordTable } from "@/features/audit/components/AuditRecordTable";

export const Route = createFileRoute("/audit-log")({
  component: AuditRecordTable,
});
