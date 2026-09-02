import { useQuery } from "@tanstack/react-query";
import { contractQuery, contractProgressQuery, contractHistoryQuery, addendaQuery } from "../hooks/contractQueries";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { StatusBadge } from "@/shared/components/status-badge";
import { Button } from "@/shared/components/button";
import { HistoryTimeline } from "./HistoryTimeline";
import { ProgressCard } from "./ProgressCard";
import { AttachmentPanel } from "./AttachmentPanel";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import type { AddendumResponse } from "../types/contractTypes";
import { useMemo, useState } from "react";

export function ContractDetail({ id }: { id: string }) {
  const q = useQuery(contractQuery(id));
  const progQ = useQuery(contractProgressQuery(id));
  const histQ = useQuery(contractHistoryQuery(id));
  const addQ = useQuery(addendaQuery({ contractId: id, size: 20 }));
  const [tab, setTab] = useState<"overview"|"addenda"|"attachments"|"history"|"progress">("overview");

  const c = q.data;
  if (q.isLoading) return <div className="text-sm text-muted-foreground">Loading...</div>;
  if (q.isError) return <div className="text-sm text-destructive">Failed to load contract</div>;
  if (!c) return null;

  const addColumns = useMemo<ColumnDef<AddendumResponse>[]>(() => [
    { accessorKey: "addendumNo", header: "NO" },
    { accessorKey: "changeType", header: "TYPE", cell: ({ row }) => <Badge variant="secondary">{row.original.changeType}</Badge> },
    { accessorKey: "effectiveFrom", header: "EFFECTIVE FROM" },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
  ], []);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>{c.contractNo} · {c.customerName}</CardTitle>
          <StatusBadge status={c.status} />
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-2 text-sm">
          <div>Service group: <Badge variant="outline">{c.serviceGroup}</Badge></div>
          <div>Value: {c.value ?? "—"} {c.currency}</div>
          <div>Valid: {c.validFrom} → {c.validTo}</div>
          <div>Payment term: {c.paymentTerm ?? "—"} · {c.billingCycle}</div>
          <div>VAT: {c.vatRate ?? "—"}%</div>
          <div>Editable: {c.editable ? "yes (CTR-01)" : "no"}</div>
          <div className="col-span-2">Description: {c.description ?? "—"}</div>
          <div className="col-span-2">Penalty: {c.penaltyTerms ?? "—"}</div>
          <div className="col-span-2">Clause: {c.serviceClause ?? "—"}</div>
          <div className="text-xs text-muted-foreground">v{c.version} · {new Date(c.createdAt).toLocaleString()} by {c.createdByName}</div>
        </CardContent>
      </Card>

      <div className="flex gap-2">
        {(["overview","addenda","attachments","history","progress"] as const).map((t) => (
          <Button key={t} size="sm" variant={tab===t?"default":"outline"} onClick={() => setTab(t)}>{t}</Button>
        ))}
      </div>

      {tab==="overview" && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <ProgressCard progress={progQ.data} isLoading={progQ.isLoading} error={progQ.error} />
          <Card><CardHeader><CardTitle className="text-base">Quick actions</CardTitle></CardHeader><CardContent className="text-xs text-muted-foreground space-y-1">
            <div>Submit requires attachment + vatRate + paymentTerm (CTR-02).</div>
            <div>Cancel ACTIVE needs <code>contract:cancel_active</code> (CTR-06).</div>
            <div>Amend approved/active via addendum (CTR-07).</div>
          </CardContent></Card>
        </div>
      )}
      {tab==="addenda" && (
        <Card>
          <CardHeader><CardTitle className="text-base">Addenda for {c.contractNo}</CardTitle></CardHeader>
          <CardContent>
            {addQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : <DataTable columns={addColumns} data={addQ.data?.content ?? []} emptyMessage="No addenda" />}
            <div className="text-xs text-muted-foreground mt-2">Addenda own workflow and history: see Addenda list for full CRUD. History for each addendum via <code>GET /addenda/{"{id}"}/history</code>.</div>
          </CardContent>
        </Card>
      )}
      {tab==="attachments" && <AttachmentPanel ownerType="CONTRACT" ownerId={c.id} />}
      {tab==="history" && <Card><CardHeader><CardTitle className="text-base">Status history</CardTitle></CardHeader><CardContent><HistoryTimeline history={histQ.data} isLoading={histQ.isLoading} /></CardContent></Card>}
      {tab==="progress" && <ProgressCard progress={progQ.data} isLoading={progQ.isLoading} error={progQ.error} />}
    </div>
  );
}
