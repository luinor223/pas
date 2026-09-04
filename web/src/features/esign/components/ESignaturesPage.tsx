import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import type { ColumnDef } from "@tanstack/react-table";
import { signingSessionsQuery } from "../hooks/esignQueries";
import { esignApi } from "../services/esignApi";
import { CANCELLABLE_SESSION_STATUSES, type SigningSessionResponse } from "../types/esignTypes";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Select } from "@/shared/components/select";
import { DataTable } from "@/shared/components/data-table";
import { StatusBadge } from "@/shared/components/status-badge";
import { PaginationControls } from "@/shared/components/pagination-controls";
import { FilterBar } from "@/shared/components/filter-bar";
import { ClearFiltersButton } from "@/shared/components/clear-filters-button";
import { RowMenu } from "@/shared/components/row-menu";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { formatDateTime } from "@/shared/lib/format";
import { statusLabel } from "@/shared/lib/labels";

const STATUSES = ["PENDING_SEND", "SIGNING", "SIGNED", "FAILED", "CANCELLED"];
const PAGE_SIZE = DEFAULT_PAGE_SIZE;

export function ESignaturesPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const canRead = useHasPermission("esign:send");
  const canCancel = useHasPermission("esign:cancel");

  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [confirmCancel, setConfirmCancel] = useState<SigningSessionResponse | null>(null);

  const listParams = { status: status || undefined, page, size: PAGE_SIZE };
  const listQ = useQuery({ ...signingSessionsQuery(listParams), enabled: canRead });
  const sessions = listQ.data?.content ?? [];
  const total = listQ.data?.totalElements ?? 0;

  const cancelMut = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) => esignApi.cancel(id, reason),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["signing-sessions"] }); setConfirmCancel(null); },
  });

  const columns = useMemo<ColumnDef<SigningSessionResponse>[]>(() => [
    {
      accessorKey: "sessionNo", header: "SESSION NO.",
      cell: ({ row }) => <Link to="/e-signatures" search={{ id: row.original.id }} className="font-medium text-blue-600 hover:underline">{row.original.sessionNo}</Link>,
    },
    { accessorKey: "documentNo", header: "DOCUMENT", cell: ({ row }) => <div><div className="font-medium">{row.original.documentNo}</div><div className="text-xs text-muted-foreground">{row.original.documentTypeCode}</div></div> },
    { accessorKey: "customerName", header: "CUSTOMER", cell: ({ row }) => <span className="text-sm">{row.original.customerName ?? "—"}</span> },
    { accessorKey: "signerName", header: "SIGNER", cell: ({ row }) => <div><div className="text-sm">{row.original.signerName ?? "—"}</div><div className="text-xs text-muted-foreground">{row.original.signerEmail ?? ""}</div></div> },
    { accessorKey: "sentAt", header: "SENT", cell: ({ row }) => <span className="text-xs">{formatDateTime(row.original.sentAt)}</span> },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      id: "actions", header: "ACTION", enableSorting: false,
      cell: ({ row }) => {
        const session = row.original;
        const items: { label: string; onClick: () => void; danger?: boolean }[] = [
          { label: "View details", onClick: () => navigate({ to: "/e-signatures", search: { id: session.id } }) },
        ];
        if (canCancel && CANCELLABLE_SESSION_STATUSES.has(session.status)) items.push({ label: "Cancel request", onClick: () => setConfirmCancel(session), danger: true });
        return <div className="text-right"><RowMenu items={items} /></div>;
      },
    },
  ], [canCancel, navigate]);

  if (!canRead) return <Card><CardContent className="p-6 text-sm">You do not have access to e-signature requests.</CardContent></Card>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <CardTitle>E-Signatures ({total})</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <FilterBar className="items-center [&_select]:bg-card">
            <Select className="w-full sm:w-44" aria-label="Filter by status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
              <option value="">Status: All</option>{STATUSES.map((s) => <option key={s} value={s}>{statusLabel(s)}</option>)}
            </Select>
            <ClearFiltersButton className="ml-auto bg-card" disabled={!status} onClick={() => { setStatus(""); setPage(0); }} />
          </FilterBar>
          <div className="text-xs text-muted-foreground">Signature requests are created when a contract, addendum, or payment statement is sent for signing.</div>
          {listQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div>
            : listQ.isError ? <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed")}</div>
            : <DataTable columns={columns} data={sessions} emptyMessage="No signature requests" pageSize={PAGE_SIZE} />}
          <PaginationControls page={page} totalPages={listQ.data?.totalPages ?? 1} pageSize={PAGE_SIZE} totalItems={total} onPageChange={setPage} />
        </CardContent>
      </Card>

      <ConfirmDialog
        open={!!confirmCancel}
        title="Cancel this signature request?"
        body={<p>Request <span className="font-medium">{confirmCancel?.sessionNo}</span> for <span className="font-medium">{confirmCancel?.documentNo}</span> will be cancelled with the provider. This cannot be undone here.</p>}
        confirmLabel="Cancel request"
        pendingLabel="Cancelling..."
        pending={cancelMut.isPending}
        error={cancelMut.isError ? cancelMut.error : undefined}
        reason={{ label: "Reason", placeholder: "Why is this request being cancelled?" }}
        onConfirm={(reason) => confirmCancel && cancelMut.mutate({ id: confirmCancel.id, reason })}
        onCancel={() => setConfirmCancel(null)}
      />
    </div>
  );
}
