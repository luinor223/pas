import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { statementQuery, statementWorkflowQuery } from "../hooks/billingQueries";
import { billingApi } from "../services/billingApi";
import { LIFECYCLE_ACTIONS, type LifecycleKey } from "../lifecycle";
import type { StatementLineResponse, StatementResponse } from "../types/billingTypes";
import { Button } from "@/shared/components/button";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Textarea } from "@/shared/components/textarea";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { DataTable } from "@/shared/components/data-table";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { StatusBadge } from "@/shared/components/status-badge";
import { DetailBackButton } from "@/shared/components/detail-back-link";
import { RowMenu } from "@/shared/components/row-menu";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { Field } from "@/shared/components/field";
import { formatDate, formatMoney } from "@/shared/lib/format";

const EDITABLE = new Set(["DRAFT", "CALCULATED"]);

export function PaymentStatementDetail({ statementNo }: { statementNo: string }) {
  const qc = useQueryClient();
  const canWrite = useHasPermission("statement:write");
  const canEsign = useHasPermission("esign:send");
  const canCancel = useHasPermission("statement:cancel_approved");

  const detailQ = useQuery(statementQuery(statementNo));
  const statement = detailQ.data;
  const workflowQ = useQuery({ ...statementWorkflowQuery(statementNo), enabled: statement?.status === "SUBMITTED" });

  const [addOpen, setAddOpen] = useState(false);
  const [editLine, setEditLine] = useState<StatementLineResponse | null>(null);
  const [confirmCancel, setConfirmCancel] = useState(false);

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["payment-statement", statementNo] });
    qc.invalidateQueries({ queryKey: ["payment-statements"] });
  };

  const actionMut = useMutation({ mutationFn: (key: LifecycleKey) => billingApi[key](statementNo), onSuccess: invalidate });
  const cancelMut = useMutation({
    mutationFn: (reason?: string) => billingApi.cancel(statementNo, reason),
    onSuccess: () => { invalidate(); setConfirmCancel(false); },
  });

  const [addForm, setAddForm] = useState({ serviceCode: "", serviceName: "", unit: "", unitPrice: "", quantity: "", note: "" });
  const addMut = useMutation({
    mutationFn: () => billingApi.addLine(statementNo, {
      serviceCode: addForm.serviceCode, serviceName: addForm.serviceName || null, unit: addForm.unit || null,
      unitPrice: Number(addForm.unitPrice), quantity: Number(addForm.quantity), note: addForm.note || null,
      version: statement?.version ?? 0,
    }),
    onSuccess: () => { invalidate(); setAddOpen(false); },
  });

  const [editForm, setEditForm] = useState({ unitPrice: "", quantity: "", note: "" });
  const editMut = useMutation({
    mutationFn: () => billingApi.editLine(statementNo, {
      lineNo: editLine?.lineNo ?? 0, unitPrice: Number(editForm.unitPrice), quantity: Number(editForm.quantity),
      note: editForm.note || null, version: statement?.version ?? 0,
    }),
    onSuccess: () => { invalidate(); setEditLine(null); },
  });

  const openEdit = (line: StatementLineResponse) => {
    setEditForm({ unitPrice: String(line.unitPrice), quantity: String(line.quantity), note: line.note ?? "" });
    setEditLine(line);
  };

  const editable = statement ? EDITABLE.has(statement.status) : false;
  const currency = statement?.currency ?? "VND";
  const perms: Record<"statement:write" | "esign:send", boolean> = { "statement:write": canWrite, "esign:send": canEsign };

  const columns = useMemo<ColumnDef<StatementLineResponse>[]>(() => {
    const cols: ColumnDef<StatementLineResponse>[] = [
      { accessorKey: "lineNo", header: "#", cell: ({ row }) => <span className="tabular-nums text-xs">{row.original.lineNo}</span> },
      { accessorKey: "serviceName", header: "SERVICE", cell: ({ row }) => <div><div className="font-medium">{row.original.serviceName}</div><div className="text-xs text-muted-foreground">{row.original.serviceCode}</div></div> },
      { accessorKey: "unit", header: "UNIT", cell: ({ row }) => <span className="text-xs">{row.original.unit}</span> },
      { accessorKey: "unitPrice", header: "UNIT PRICE", cell: ({ row }) => <span className="tabular-nums">{formatMoney(row.original.unitPrice, currency)}</span> },
      { accessorKey: "quantity", header: "QTY", cell: ({ row }) => <span className="tabular-nums">{row.original.quantity}</span> },
      { accessorKey: "amount", header: "AMOUNT", cell: ({ row }) => <span className="tabular-nums font-medium">{formatMoney(row.original.amount, currency)}</span> },
      { accessorKey: "source", header: "SOURCE", cell: ({ row }) => <span className="text-xs text-muted-foreground">{row.original.source}</span> },
    ];
    if (canWrite && editable) {
      cols.push({
        id: "actions", header: "", enableSorting: false,
        cell: ({ row }) => <div className="text-right"><RowMenu items={[{ label: "Edit line", onClick: () => openEdit(row.original) }]} /></div>,
      });
    }
    return cols;
  }, [canWrite, editable, currency]);

  if (detailQ.isLoading) return <div className="text-sm text-muted-foreground">Loading...</div>;
  if (detailQ.isError || !statement) return <div className="text-sm text-destructive">{getApiErrorMessage(detailQ.error, "Statement not found")}</div>;

  const s: StatementResponse = statement;
  const actions = LIFECYCLE_ACTIONS.filter((a) => perms[a.perm] && a.statuses.includes(s.status));

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex items-center gap-3">
            <DetailBackButton to="/payment-statements" label="Back to payment statements" />
            <div>
              <CardTitle className="flex items-center gap-2">{s.statementNo}<StatusBadge status={s.status} /></CardTitle>
              <div className="mt-0.5 text-sm text-muted-foreground">{s.customerName ?? "—"} · {s.contractNo}</div>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {actions.map((a) => (
              <Button key={a.key} variant={a.primary ? undefined : "outline"} onClick={() => actionMut.mutate(a.key)} disabled={actionMut.isPending && actionMut.variables === a.key}>{a.label}</Button>
            ))}
            {canCancel && (s.status === "APPROVED" || s.status === "SIGNED") && <Button variant="outline" onClick={() => setConfirmCancel(true)}>Cancel</Button>}
          </div>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Field label="Period">{s.periodCode}</Field>
            <Field label="Coverage">{formatDate(s.periodStart)} — {formatDate(s.periodEnd)}</Field>
            <Field label="Price list">{s.priceListNo ? `${s.priceListNo} v${s.priceListVersionNo ?? "?"}` : "—"}</Field>
            <Field label="Payment term">{s.paymentTerm ?? "—"}</Field>
            <Field label="Subtotal"><span className="tabular-nums">{formatMoney(s.subtotal, currency)}</span></Field>
            <Field label={`Tax${s.vatRate != null ? ` (${s.vatRate}%)` : ""}`}><span className="tabular-nums">{formatMoney(s.taxAmount, currency)}</span></Field>
            <Field label="Total"><span className="tabular-nums font-semibold">{formatMoney(s.totalAmount, currency)}</span></Field>
            <Field label="Due date">{formatDate(s.dueDate)}</Field>
          </div>
          {s.adjustsStatementId ? <div className="mt-3 text-xs text-muted-foreground">This is an adjustment statement.</div> : null}
          {actionMut.isError ? <div className="mt-3 text-xs text-destructive">{getApiErrorMessage(actionMut.error, "Action failed")}</div> : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Line items ({s.lines.length})</CardTitle>
          {canWrite && editable && <Button size="sm" onClick={() => { setAddForm({ serviceCode: "", serviceName: "", unit: "", unitPrice: "", quantity: "", note: "" }); setAddOpen(true); }}>+ Add line</Button>}
        </CardHeader>
        <CardContent>
          <DataTable columns={columns} data={s.lines} emptyMessage="No line items" pageSize={s.lines.length || 1} />
        </CardContent>
      </Card>

      {s.status === "SUBMITTED" && (
        <Card>
          <CardHeader><CardTitle>Approval workflow</CardTitle></CardHeader>
          <CardContent className="text-sm">
            {workflowQ.isLoading ? <span className="text-muted-foreground">Loading progress...</span>
              : workflowQ.isError ? <span className="text-destructive">{getApiErrorMessage(workflowQ.error, "Could not load workflow")}</span>
              : <pre className="overflow-x-auto rounded-lg bg-muted p-3 text-xs">{JSON.stringify(workflowQ.data?.workflowInstance ?? {}, null, 2)}</pre>}
          </CardContent>
        </Card>
      )}

      <ConfirmDialog
        open={confirmCancel}
        title="Cancel this payment statement?"
        body={<p>Statement <span className="font-medium">{s.statementNo}</span> will be cancelled. This cannot be undone here.</p>}
        confirmLabel="Cancel statement"
        pendingLabel="Cancelling..."
        pending={cancelMut.isPending}
        error={cancelMut.isError ? cancelMut.error : undefined}
        reason={{ label: "Reason", placeholder: "Why is this statement being cancelled?" }}
        onConfirm={(reason) => cancelMut.mutate(reason)}
        onCancel={() => setConfirmCancel(false)}
      />

      <Dialog open={addOpen} onOpenChange={setAddOpen}>
        <DialogContent className="max-w-xl">
          <DialogHeader><DialogTitle>Add line item</DialogTitle></DialogHeader>
          <form onSubmit={(e) => { e.preventDefault(); addMut.mutate(); }} className="space-y-3">
            <div className="grid grid-cols-2 gap-2">
              <div><Label>Service code *</Label><Input value={addForm.serviceCode} onChange={(e) => setAddForm({ ...addForm, serviceCode: e.target.value })} /></div>
              <div><Label>Service name</Label><Input value={addForm.serviceName} onChange={(e) => setAddForm({ ...addForm, serviceName: e.target.value })} /></div>
              <div><Label>Unit</Label><Input value={addForm.unit} onChange={(e) => setAddForm({ ...addForm, unit: e.target.value })} /></div>
              <div><Label>Unit price *</Label><Input type="number" step="0.01" value={addForm.unitPrice} onChange={(e) => setAddForm({ ...addForm, unitPrice: e.target.value })} /></div>
              <div><Label>Quantity *</Label><Input type="number" step="0.01" value={addForm.quantity} onChange={(e) => setAddForm({ ...addForm, quantity: e.target.value })} /></div>
            </div>
            <div><Label>Note</Label><Textarea value={addForm.note} onChange={(e) => setAddForm({ ...addForm, note: e.target.value })} /></div>
            {addMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(addMut.error, "Add failed")}</div>}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setAddOpen(false)}>Cancel</Button>
              <Button type="submit" disabled={addMut.isPending || !addForm.serviceCode || !addForm.unitPrice || !addForm.quantity}>{addMut.isPending ? "Adding..." : "Add line"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={!!editLine} onOpenChange={(o) => !o && setEditLine(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader><DialogTitle>Edit line {editLine?.lineNo} — {editLine?.serviceName}</DialogTitle></DialogHeader>
          <form onSubmit={(e) => { e.preventDefault(); editMut.mutate(); }} className="space-y-3">
            <div className="grid grid-cols-2 gap-2">
              <div><Label>Unit price *</Label><Input type="number" step="0.01" value={editForm.unitPrice} onChange={(e) => setEditForm({ ...editForm, unitPrice: e.target.value })} /></div>
              <div><Label>Quantity *</Label><Input type="number" step="0.01" value={editForm.quantity} onChange={(e) => setEditForm({ ...editForm, quantity: e.target.value })} /></div>
            </div>
            <div><Label>Note</Label><Textarea value={editForm.note} onChange={(e) => setEditForm({ ...editForm, note: e.target.value })} /></div>
            {editMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(editMut.error, "Edit failed")}</div>}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setEditLine(null)}>Cancel</Button>
              <Button type="submit" disabled={editMut.isPending}>{editMut.isPending ? "Saving..." : "Save"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
