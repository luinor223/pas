import { useQuery } from "@tanstack/react-query";
import { contractQuery, contractProgressQuery, contractHistoryQuery, addendaQuery, attachmentsQuery, customerQuery } from "../hooks/contractQueries";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { StatusBadge } from "@/shared/components/status-badge";
import { Button } from "@/shared/components/button";
import { HistoryTimeline } from "./HistoryTimeline";
import { AttachmentPanel } from "./AttachmentPanel";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import type { AddendumResponse } from "../types/contractTypes";
import { useMemo, useState } from "react";
import { Link } from "@tanstack/react-router";
import { formatDate, formatDateTime, formatMoney } from "@/shared/lib/format";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { DetailBackButton } from "@/shared/components/detail-back-link";
import { TabBar, type TabItem } from "@/shared/components/tab-bar";

type Tab = "overview" | "addenda" | "approval-history" | "attachments";

const TABS: readonly TabItem<Tab>[] = [
  { value: "overview", label: "Overview" },
  { value: "addenda", label: "Addenda" },
  { value: "approval-history", label: "Approval History" },
  { value: "attachments", label: "Attachments" },
];

export function ContractDetail({ id, initialTab }: { id: string; initialTab?: string }) {
  const q = useQuery(contractQuery(id));
  const progQ = useQuery(contractProgressQuery(id));
  const histQ = useQuery(contractHistoryQuery(id));
  const addQ = useQuery(addendaQuery({ contractId: id, size: DEFAULT_PAGE_SIZE }));
  const attQ = useQuery(attachmentsQuery("CONTRACT", id));
  const [tab, setTab] = useState<Tab>(
    initialTab === "attachments" ? "attachments" : "overview",
  );

  const c = q.data;
  const custQ = useQuery(customerQuery(c?.customerId ?? ""));
  const customer = custQ.data;

  const addColumns = useMemo<ColumnDef<AddendumResponse>[]>(() => [
    { accessorKey: "addendumNo", header: "NO" },
    { accessorKey: "changeType", header: "TYPE", cell: ({ row }) => <Badge variant="secondary">{row.original.changeType}</Badge> },
    { accessorKey: "effectiveFrom", header: "EFFECTIVE FROM" },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
  ], []);

  if (q.isLoading) return <div className="text-sm text-muted-foreground">Loading...</div>;
  if (q.isError) return <div className="text-sm text-destructive">Failed to load contract</div>;
  if (!c) return null;

  const readOnly = c.status !== "DRAFT" && c.status !== "REVISION_REQUESTED";
  const pdf = (attQ.data ?? []).find((a) => (a.contentType ?? "").includes("pdf") || a.fileName.toLowerCase().endsWith(".pdf"));
  const progress = progQ.data;
  const waiting = progress?.currentStep
    ? `Waiting on ${progress.currentStep.name} — Assignee: ${progress.currentStep.assigneeNames.join(", ") || "—"}`
    : progress?.workflowState === "INITIALIZATION_PENDING"
      ? "Your submission is being prepared for approval"
      : null;

  const steps = progress?.steps ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <DetailBackButton to="/contracts" label="Back to contracts" />
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-bold">{c.contractNo}</h2>
              <StatusBadge status={c.status} />
            </div>
            <div className="text-sm text-muted-foreground">{c.serviceGroup.toLowerCase().replace(/_/g, " ")} services · {c.customerName}</div>
          </div>
        </div>
        <div className="flex gap-2 items-center">
          <Button
            size="sm"
            variant="outline"
            disabled={!pdf}
            title={pdf ? `Download ${pdf.fileName}` : "Upload an attachment before downloading a PDF"}
            onClick={() => pdf && window.open(`/api/v1/attachments/${pdf.id}`, "_blank")}
          >
            Download PDF
          </Button>
          {readOnly && <Badge variant="secondary">Read-only while {c.status.toLowerCase().replace(/_/g, " ")}</Badge>}
        </div>
      </div>

      <TabBar tabs={TABS} value={tab} onChange={setTab} />

      {tab === "overview" && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <div className="lg:col-span-2 space-y-4">
            <Card>
              <CardHeader><CardTitle className="text-base">General information</CardTitle></CardHeader>
              <CardContent className="grid grid-cols-3 gap-3 text-sm">
                <div><div className="text-xs text-muted-foreground">CONTRACT NUMBER</div><div>{c.contractNo}</div></div>
                <div><div className="text-xs text-muted-foreground">CUSTOMER</div><div><Link to="/customers" search={{ id: c.customerId } as never} className="text-blue-600 hover:underline">{c.customerName}</Link></div></div>
                <div><div className="text-xs text-muted-foreground">TAX ID</div><div>{customer?.taxCode ?? (custQ.isLoading ? "…" : "—")}</div></div>
                <div><div className="text-xs text-muted-foreground">SERVICE GROUP</div><div className="capitalize">{c.serviceGroup.toLowerCase().replace(/_/g, " ")}</div></div>
                <div><div className="text-xs text-muted-foreground">CONTRACT VALUE</div><div className="tabular-nums">{formatMoney(c.value, c.currency)}</div></div>
                <div><div className="text-xs text-muted-foreground">CURRENCY</div><div>{c.currency}</div></div>
                <div><div className="text-xs text-muted-foreground">EFFECTIVE FROM</div><div>{formatDate(c.validFrom)}</div></div>
                <div><div className="text-xs text-muted-foreground">EXPIRY DATE</div><div>{formatDate(c.validTo)}</div></div>
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle className="text-base">Commercial &amp; payment terms</CardTitle></CardHeader>
              <CardContent className="grid grid-cols-2 gap-3 text-sm">
                <div><div className="text-xs text-muted-foreground">PAYMENT TERM</div><div>{c.paymentTerm ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">BILLING CYCLE</div><div className="capitalize">{c.billingCycle.toLowerCase()}</div></div>
                <div><div className="text-xs text-muted-foreground">VAT RATE</div><div>{c.vatRate != null ? `${c.vatRate}%` : "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">PENALTY</div><div>{c.penaltyTerms ?? "—"}</div></div>
                <div className="col-span-2"><div className="text-xs text-muted-foreground">SERVICE CLAUSE</div><div>{c.serviceClause ?? "—"}</div></div>
                {c.description && <div className="col-span-2"><div className="text-xs text-muted-foreground">DESCRIPTION</div><div>{c.description}</div></div>}
              </CardContent>
            </Card>
            <AttachmentPanel ownerType="CONTRACT" ownerId={c.id} />
          </div>

          <div className="space-y-4">
            <Card>
              <CardHeader><CardTitle className="text-base">Approval workflow</CardTitle></CardHeader>
              <CardContent className="space-y-3 text-sm">
                {progQ.isLoading ? <div className="text-muted-foreground">Loading...</div> : waiting ? (
                  <div className="rounded bg-amber-50 border border-amber-200 p-2 text-xs text-amber-800">{waiting}</div>
                ) : null}
                {steps.length === 0 ? (
                  <div className="text-xs text-muted-foreground">
                    {progress?.workflowState === "INITIALIZATION_PENDING"
                      ? "Your submission is being prepared for approval. This usually takes a moment."
                      : "Submit the document to begin approval."}
                  </div>
                ) : (
                  <div className="space-y-2">
                    {steps.map((s) => (
                      <div key={s.stepNo} className="flex gap-2 items-start">
                        <span className={`mt-1 h-4 w-4 rounded-full border flex items-center justify-center text-[10px] ${s.status === "APPROVED" ? "bg-green-600 text-white border-green-600" : s.status === "ACTIVE" ? "bg-amber-500 border-amber-500" : "border-gray-300"}`}>
                          {s.status === "APPROVED" ? "✓" : ""}
                        </span>
                        <div>
                          <div className="font-medium text-sm">{s.name} <span className="text-xs text-muted-foreground">· {s.approverRole}</span></div>
                          <div className="text-xs text-muted-foreground">
                            {s.action ? `${s.action.action} by ${s.action.actorName}${s.action.comment ? ` — "${s.action.comment}"` : ""}` : `${s.assigneeNames.join(", ") || "—"}${s.status === "ACTIVE" ? " - in progress" : ""}`}
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle className="text-base">Record metadata</CardTitle></CardHeader>
              <CardContent className="grid grid-cols-2 gap-2 text-sm">
                <div><div className="text-xs text-muted-foreground">CREATED BY</div><div>{c.createdByName ?? "—"}</div></div>
                <div><div className="text-xs text-muted-foreground">CREATED AT</div><div className="text-xs">{formatDateTime(c.createdAt)}</div></div>
                <div><div className="text-xs text-muted-foreground">LAST MODIFIED</div><div className="text-xs">{formatDateTime(c.updatedAt)}</div></div>
                <div><div className="text-xs text-muted-foreground">LINKED RECORDS</div><div>{addQ.data?.totalElements ?? "…"} addenda</div></div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {tab === "addenda" && (
        <Card>
          <CardHeader><CardTitle className="text-base">Addenda for {c.contractNo}</CardTitle></CardHeader>
          <CardContent>
            {addQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : <DataTable columns={addColumns} data={addQ.data?.content ?? []} emptyMessage="No addenda" pageSize={DEFAULT_PAGE_SIZE} />}
          </CardContent>
        </Card>
      )}
      {tab === "approval-history" && <Card><CardHeader><CardTitle className="text-base">Approval History</CardTitle></CardHeader><CardContent><HistoryTimeline history={histQ.data} isLoading={histQ.isLoading} /></CardContent></Card>}
      {tab === "attachments" && <AttachmentPanel ownerType="CONTRACT" ownerId={c.id} />}
    </div>
  );
}
