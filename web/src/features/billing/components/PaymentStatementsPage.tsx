import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import type { ColumnDef } from "@tanstack/react-table";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { statementsQuery } from "../hooks/billingQueries";
import { billingApi } from "../services/billingApi";
import { LIFECYCLE_ACTIONS, type LifecycleKey } from "../lifecycle";
import type { StatementResponse } from "../types/billingTypes";
import { Button } from "@/shared/components/button";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { DataTable } from "@/shared/components/data-table";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { StatusBadge } from "@/shared/components/status-badge";
import { PaginationControls } from "@/shared/components/pagination-controls";
import { FilterBar } from "@/shared/components/filter-bar";
import { SearchInput } from "@/shared/components/search-input";
import { ClearFiltersButton } from "@/shared/components/clear-filters-button";
import { RowMenu } from "@/shared/components/row-menu";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { ContractPicker } from "@/features/contract/components/ContractPicker";
import { formatDate, formatMoney } from "@/shared/lib/format";
import { statusLabel } from "@/shared/lib/labels";

const STATUSES = ["DRAFT", "CALCULATED", "RECONCILED", "SUBMITTED", "APPROVED", "SIGNING", "SIGNED", "ISSUED", "REJECTED", "REVISION", "CANCELLED"];
const PAGE_SIZE = DEFAULT_PAGE_SIZE;

const schema = z.object({
  contractId: z.string().min(1, "Required"),
  periodCode: z.string().min(1, "Required"),
});
type FormData = z.infer<typeof schema>;

export function PaymentStatementsPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const canRead = useHasPermission("statement:read");
  const canWrite = useHasPermission("statement:write");
  const canEsign = useHasPermission("esign:send");
  const canCancel = useHasPermission("statement:cancel_approved");

  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [openCreate, setOpenCreate] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState<StatementResponse | null>(null);

  const hasFilters = !!(q || status);
  const listParams = { q: q || undefined, status: status || undefined, page, size: PAGE_SIZE };
  const listQ = useQuery({ ...statementsQuery(listParams), enabled: canRead });
  const statements = listQ.data?.content ?? [];
  const total = listQ.data?.totalElements ?? 0;

  const { register, handleSubmit, reset, setValue, watch, formState: { errors } } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { contractId: "", periodCode: "" },
  });
  const selectedContractId = watch("contractId");

  const calcMut = useMutation({
    mutationFn: (data: FormData) => billingApi.calculate(data),
    onSuccess: (created) => {
      qc.invalidateQueries({ queryKey: ["payment-statements"] });
      setOpenCreate(false);
      reset();
      navigate({ to: "/payment-statements", search: { id: created.id } });
    },
  });
  const invalidateList = () => qc.invalidateQueries({ queryKey: ["payment-statements"] });
  const actionMut = useMutation({
    mutationFn: ({ key, no }: { key: LifecycleKey; no: string }) => billingApi[key](no),
    onSuccess: invalidateList,
  });
  const cancelMut = useMutation({
    mutationFn: ({ no, reason }: { no: string; reason?: string }) => billingApi.cancel(no, reason),
    onSuccess: () => { invalidateList(); setConfirmCancel(null); },
  });

  const columns = useMemo<ColumnDef<StatementResponse>[]>(() => [
    {
      accessorKey: "statementNo", header: "STATEMENT NO.",
      cell: ({ row }) => <Link to="/payment-statements" search={{ id: row.original.id }} className="font-medium text-blue-600 hover:underline">{row.original.statementNo}</Link>,
    },
    { accessorKey: "customerName", header: "CUSTOMER", cell: ({ row }) => <span className="font-medium">{row.original.customerName ?? "—"}</span> },
    { accessorKey: "contractNo", header: "CONTRACT", cell: ({ row }) => <span className="text-sm">{row.original.contractNo}</span> },
    { accessorKey: "periodCode", header: "PERIOD", cell: ({ row }) => <span className="text-sm tabular-nums">{row.original.periodCode}</span> },
    { accessorKey: "totalAmount", header: "TOTAL", cell: ({ row }) => <span className="tabular-nums">{formatMoney(row.original.totalAmount, row.original.currency ?? "VND")}</span> },
    { accessorKey: "dueDate", header: "DUE", cell: ({ row }) => <span className="text-xs">{formatDate(row.original.dueDate)}</span> },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      id: "actions", header: "ACTION", enableSorting: false,
      cell: ({ row }) => {
        const s = row.original;
        const perms: Record<"statement:write" | "esign:send", boolean> = { "statement:write": canWrite, "esign:send": canEsign };
        const items: { label: string; onClick: () => void; danger?: boolean }[] = [
          { label: "View details", onClick: () => navigate({ to: "/payment-statements", search: { id: s.id } }) },
        ];
        LIFECYCLE_ACTIONS.filter((a) => perms[a.perm] && a.statuses.includes(s.status)).forEach((a) =>
          items.push({ label: a.label, onClick: () => actionMut.mutate({ key: a.key, no: s.id }) }));
        if (canCancel && (s.status === "APPROVED" || s.status === "SIGNED")) items.push({ label: "Cancel", onClick: () => setConfirmCancel(s), danger: true });
        return <div className="text-right"><RowMenu items={items} /></div>;
      },
    },
  ], [canWrite, canEsign, canCancel, navigate]);

  if (!canRead) return <Card><CardContent className="p-6 text-sm">You do not have access to payment statements.</CardContent></Card>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <CardTitle>Payment Statements ({total})</CardTitle>
          <div className="flex items-center gap-2">
            <SearchInput className="w-56 lg:w-72" label="Search statements" placeholder="Search no/customer/contract..." value={q} onChange={(value) => { setQ(value); setPage(0); }} />
            {canWrite && <Button onClick={() => { reset({ contractId: "", periodCode: "" }); setOpenCreate(true); }}>+ New Statement</Button>}
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <FilterBar className="items-center [&_input]:bg-card [&_select]:bg-card">
            <Select className="w-full sm:w-44" aria-label="Filter by status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
              <option value="">Status: All</option>{STATUSES.map((s) => <option key={s} value={s}>{statusLabel(s)}</option>)}
            </Select>
            <ClearFiltersButton className="ml-auto bg-card" disabled={!hasFilters} onClick={() => { setQ(""); setStatus(""); setPage(0); }} />
          </FilterBar>
          {listQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div>
            : listQ.isError ? <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed")}</div>
            : <DataTable columns={columns} data={statements} emptyMessage="No payment statements" pageSize={PAGE_SIZE} />}
          <PaginationControls page={page} totalPages={listQ.data?.totalPages ?? 1} pageSize={PAGE_SIZE} totalItems={total} onPageChange={setPage} />
          {actionMut.isError ? <div className="text-xs text-destructive">{getApiErrorMessage(actionMut.error, "Action failed")}</div> : null}
        </CardContent>
      </Card>

      <ConfirmDialog
        open={!!confirmCancel}
        title="Cancel this payment statement?"
        body={<p>Statement <span className="font-medium">{confirmCancel?.statementNo}</span> for <span className="font-medium">{confirmCancel?.customerName}</span> will be cancelled. This cannot be undone here.</p>}
        confirmLabel="Cancel statement"
        pendingLabel="Cancelling..."
        pending={cancelMut.isPending}
        error={cancelMut.isError ? cancelMut.error : undefined}
        reason={{ label: "Reason", placeholder: "Why is this statement being cancelled?" }}
        onConfirm={(reason) => confirmCancel && cancelMut.mutate({ no: confirmCancel.id, reason })}
        onCancel={() => setConfirmCancel(null)}
      />

      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>Calculate payment statement</DialogTitle>
            <p className="text-sm text-muted-foreground">Build a statement from confirmed operational volumes and the effective prices for that month.</p>
          </DialogHeader>
          <form onSubmit={handleSubmit((d) => calcMut.mutate(d))} className="space-y-3">
            <p className="rounded-md border bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
              Before calculating, use the Process guide in the bottom-left corner to confirm the contract, prices, and locked volumes are ready.
            </p>
            <div>
              <ContractPicker value={selectedContractId} onChange={(id) => setValue("contractId", id, { shouldValidate: true })} label="Contract" requirement="draft" emptyHint="Choose an active contract, or an expired contract for its final billing month." placeholder="Search contract number or customer..." statuses={["ACTIVE", "EXPIRED"]} />
              {errors.contractId && <p className="text-xs text-destructive">{errors.contractId.message}</p>}
            </div>
            <div>
              <Label htmlFor="billing-period-code">Period *</Label>
              <Input id="billing-period-code" type="month" {...register("periodCode")} />
              {errors.periodCode && <p className="text-xs text-destructive">{errors.periodCode.message}</p>}
              <p className="mt-1 text-xs text-muted-foreground">Select an existing, locked month from Volume Records.</p>
            </div>
            {calcMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(calcMut.error, "Calculation failed")}</div>}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpenCreate(false)}>Cancel</Button>
              <Button type="submit" disabled={calcMut.isPending}>{calcMut.isPending ? "Checking prerequisites..." : "Check and calculate"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
