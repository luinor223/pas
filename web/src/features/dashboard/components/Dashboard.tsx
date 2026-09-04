import { FileText, CheckSquare, ReceiptText, PenLine } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { useQueries, useQuery } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { StatCard } from "@/shared/components/stat-card";
import { DataTable } from "@/shared/components/data-table";
import { statusTextTone } from "@/shared/lib/status-tone";
import { cn } from "@/shared/lib/cn";
import { formatRelative } from "@/shared/lib/format";
import { humanize } from "@/shared/lib/text";
import { documentTypeLabel } from "@/shared/lib/labels";
import { documentTarget, auditRecordTarget } from "@/shared/lib/document-links";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { approvalInboxQuery } from "@/features/approval/hooks/approvalQueries";
import { contractsQuery } from "@/features/contract/hooks/contractQueries";
import { statementsQuery } from "@/features/billing/hooks/billingQueries";
import { signingSessionsQuery } from "@/features/esign/hooks/esignQueries";
import { auditRecordsQuery } from "@/features/audit/hooks/auditQueries";
import { auditActivityLabel } from "@/features/audit/auditOptions";
import type { ApprovalInboxItem } from "@/features/approval/types/approvalTypes";
import type { AuditRecordResponse } from "@/features/audit/types/auditTypes";

// Contract-status breakdown; each bar is one count-only list call read from its page total.
const STATUS_BARS = [
  { status: "ACTIVE", label: "Active", color: "#1d4ed8" },
  { status: "UNDER_REVIEW", label: "Under review", color: "#ea8a1e" },
  { status: "DRAFT", label: "Draft", color: "#94a3b8" },
  { status: "EXPIRED", label: "Expired", color: "#cbd5e1" },
  { status: "REJECTED", label: "Rejected", color: "#dc2626" },
];

export function Dashboard() {
  const canContracts = useHasPermission("contract:read");
  const canStatements = useHasPermission("statement:read");
  const canAudit = useHasPermission("audit:view_all");

  const statusCounts = useQueries({
    queries: STATUS_BARS.map((bar) => ({ ...contractsQuery({ status: bar.status, size: 1 }), enabled: canContracts })),
  });
  const approvals = useQuery(approvalInboxQuery("ASSIGNED", { page: 0, size: 5 }));
  const statements = useQuery({ ...statementsQuery({ size: 1 }), enabled: canStatements });
  const awaitingSend = useQuery(signingSessionsQuery({ status: "PENDING_SEND", size: 1 }));
  const awaitingSign = useQuery(signingSessionsQuery({ status: "SIGNING", size: 1 }));
  const activity = useQuery({ ...auditRecordsQuery({ size: 6 }), enabled: canAudit });

  const contractCount = (status: string) => {
    const query = statusCounts[STATUS_BARS.findIndex((bar) => bar.status === status)];
    return { value: query?.data?.totalElements, loading: query?.isLoading ?? false };
  };
  const bars = STATUS_BARS.map((bar) => ({ ...bar, value: contractCount(bar.status).value ?? 0 }));
  const max = Math.max(1, ...bars.map((b) => b.value));
  const awaitingSignature = (awaitingSend.data?.totalElements ?? 0) + (awaitingSign.data?.totalElements ?? 0);

  return (
    <div className="space-y-6">
      {/* KPIs */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {canContracts && (
          <StatCard
            label="Active Contracts"
            value={kpi(contractCount("ACTIVE").value, contractCount("ACTIVE").loading)}
            icon={<FileText size={18} />}
            foot={`${contractCount("UNDER_REVIEW").value ?? 0} under review`}
          />
        )}
        <StatCard
          label="Pending My Approval"
          value={kpi(approvals.data?.totalItems, approvals.isLoading)}
          icon={<CheckSquare size={18} />}
        />
        {canStatements && (
          <StatCard
            label="Payment Statements"
            value={kpi(statements.data?.totalElements, statements.isLoading)}
            icon={<ReceiptText size={18} />}
          />
        )}
        <StatCard
          label="Awaiting Signature"
          value={kpi(awaitingSignature, awaitingSend.isLoading || awaitingSign.isLoading)}
          icon={<PenLine size={18} />}
        />
      </div>

      {/* Approvals + activity */}
      <div className={cn("grid grid-cols-1 gap-6", canAudit && "lg:grid-cols-[1.9fr_1fr]")}>
        <section className="rounded-xl border border-border bg-card">
          <div className="flex items-center justify-between px-5 py-4">
            <h2 className="text-[15px] font-semibold">Pending my approval</h2>
            <Link to="/approvals" className="text-sm font-medium text-primary hover:underline">View all</Link>
          </div>
          <div className="px-5 pb-5">
            {approvals.isLoading ? (
              <p className="py-6 text-sm text-muted-foreground">Loading approvals...</p>
            ) : approvals.isError ? (
              <p className="py-6 text-sm text-destructive">Could not load approvals.</p>
            ) : (
              <DataTable columns={approvalColumns} data={approvals.data?.items ?? []} emptyMessage="Nothing pending" />
            )}
          </div>
        </section>

        {canAudit && (
          <section className="rounded-xl border border-border bg-card p-5">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-[15px] font-semibold">Recent activity</h2>
              <Link to="/audit-log" className="text-sm font-medium text-primary hover:underline">View all</Link>
            </div>
            {activity.isLoading ? (
              <p className="text-sm text-muted-foreground">Loading activity...</p>
            ) : activity.isError ? (
              <p className="text-sm text-destructive">Could not load activity.</p>
            ) : (activity.data?.content.length ?? 0) === 0 ? (
              <p className="text-sm text-muted-foreground">No recent activity.</p>
            ) : (
              <ul className="space-y-4">
                {activity.data?.content.map((record) => <ActivityRow key={record.id} record={record} />)}
              </ul>
            )}
          </section>
        )}
      </div>

      {/* Status chart */}
      {canContracts && (
        <section className="rounded-xl border border-border bg-card p-5">
          <h2 className="mb-4 text-[15px] font-semibold">Contracts by status</h2>
          <div className="space-y-3">
            {bars.map((b) => (
              <div key={b.label} className="flex items-center gap-4">
                <span className="w-28 shrink-0 text-[13px] text-muted-foreground">{b.label}</span>
                <div className="h-2.5 flex-1 rounded-full bg-muted">
                  <div className="h-full rounded-full" style={{ width: `${(b.value / max) * 100}%`, background: b.color }} />
                </div>
                <span className="w-8 shrink-0 text-right text-[13px] font-medium tnum">{b.value}</span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function kpi(value: number | undefined, loading: boolean) {
  if (loading) return "—";
  return (value ?? 0).toLocaleString();
}

const approvalColumns: ColumnDef<ApprovalInboxItem>[] = [
  {
    accessorKey: "documentNo",
    header: "Document",
    cell: ({ row }) => {
      const item = row.original;
      const target = documentTarget(item.documentTypeCode, item.documentId);
      return target
        ? <Link to={target.to} search={target.search as never} className="font-mono text-[13px] text-primary hover:underline">{item.documentNo}</Link>
        : <span className="font-mono text-[13px]">{item.documentNo}</span>;
    },
  },
  { accessorKey: "customerName", header: "Customer", cell: ({ row }) => row.original.customerName || "—" },
  { accessorKey: "documentTypeCode", header: "Type", cell: ({ row }) => <span className="text-muted-foreground">{documentTypeLabel(row.original.documentTypeCode)}</span> },
  { accessorKey: "priority", header: "Priority", enableSorting: false, cell: ({ row }) => humanize(row.original.priority) },
  { accessorKey: "stepActivatedAt", header: "Waiting", cell: ({ row }) => <span className="text-muted-foreground">{formatRelative(row.original.stepActivatedAt ?? row.original.createdAt)}</span> },
];

function ActivityRow({ record }: { record: AuditRecordResponse }) {
  const target = auditRecordTarget(record.entityType, record.entityId, record.entityNo, record.changes);
  const label = `${auditActivityLabel(record.action)} ${record.entityNo ?? humanize(record.entityType)}`;
  return (
    <li className="flex gap-3">
      <span className={cn("mt-1.5 h-2 w-2 shrink-0 rounded-full bg-current", statusTextTone(record.afterStatus ?? record.beforeStatus ?? ""))} />
      <div className="min-w-0">
        <p className="text-[13px] leading-snug">
          <span className="font-medium">{record.actorName ?? "System"}</span>{" "}
          {target
            ? <Link to={target.to} search={target.search as never} className="hover:underline">{label}</Link>
            : label}
        </p>
        <p className="mt-0.5 text-xs text-muted-foreground">{formatRelative(record.occurredAt)}</p>
      </div>
    </li>
  );
}
