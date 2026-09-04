import { useQuery, useQueryClient } from "@tanstack/react-query";
import { contractQuery, contractProgressQuery, contractHistoryQuery, addendaQuery, attachmentsQuery, customerQuery } from "../hooks/contractQueries";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { StatusBadge } from "@/shared/components/status-badge";
import { Button } from "@/shared/components/button";
import { HistoryTimeline } from "./HistoryTimeline";
import { AttachmentPanel } from "./AttachmentPanel";
import { ApprovalProgressPanel } from "./ApprovalProgressPanel";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import type { AddendumResponse } from "../types/contractTypes";
import { useCallback, useMemo } from "react";
import { Link, useNavigate } from "@tanstack/react-router";
import { formatDate, formatDateTime, formatMoney } from "@/shared/lib/format";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { DetailBackButton } from "@/shared/components/detail-back-link";
import { TabBar, type TabItem } from "@/shared/components/tab-bar";
import { DocumentSigningPanel } from "./DocumentSigningPanel";
import { useRecoverOutOfRangePage } from "@/shared/hooks/use-recover-out-of-range-page";

type Tab = "overview" | "addenda" | "approval-history" | "attachments";

const TABS: readonly TabItem<Tab>[] = [
  { value: "overview", label: "Overview" },
  { value: "addenda", label: "Addenda" },
  { value: "approval-history", label: "Approval History" },
  { value: "attachments", label: "Attachments" },
];

export function ContractDetail({ id, tab: requestedTab, relatedPage = 0, relatedCursor }: {
  id: string; tab?: Tab; relatedPage?: number; relatedCursor?: string;
}) {
  const navigate = useNavigate({ from: "/contracts" });
  const queryClient = useQueryClient();
  const tab = requestedTab ?? "overview";
  const q = useQuery(contractQuery(id));
  const progQ = useQuery(contractProgressQuery(id));
  const histQ = useQuery(contractHistoryQuery(id));
  const addQ = useQuery(addendaQuery({ contractId: id, page: relatedPage, size: DEFAULT_PAGE_SIZE, cursor: relatedCursor }));
  const attQ = useQuery(attachmentsQuery("CONTRACT", id));
  const setTab = (next: Tab) => navigate({
    to: "/contracts",
    search: (previous) => ({
      ...previous,
      tab: next === "overview" ? undefined : next,
      relatedPage: next === "addenda" ? previous.relatedPage : undefined,
      relatedCursor: next === "addenda" ? previous.relatedCursor : undefined,
    }),
  });
  const changeRelatedPage = (nextPage: number) => navigate({
    to: "/contracts",
    search: (previous) => ({
      ...previous,
      relatedPage: nextPage || undefined,
      relatedCursor: previous.relatedCursor ?? addQ.data?.cursor,
    }),
  });
  const recoverRelated = useCallback(() => {
    queryClient.removeQueries({ queryKey: ["addenda"] });
    navigate({
      to: "/contracts",
      search: (previous) => ({ ...previous, relatedPage: undefined, relatedCursor: undefined }),
      replace: true,
    });
  }, [navigate, queryClient]);
  useRecoverOutOfRangePage({
    ready: addQ.isSuccess && tab === "addenda",
    page: relatedPage,
    totalPages: addQ.data?.totalPages ?? 0,
    totalItems: addQ.data?.totalElements ?? 0,
    recover: recoverRelated,
  });

  const c = q.data;
  const custQ = useQuery(customerQuery(c?.customerId ?? ""));
  const customer = custQ.data;

  const addColumns = useMemo<ColumnDef<AddendumResponse>[]>(() => [
    {
      accessorKey: "addendumNo", header: "NO",
      cell: ({ row }) => <Link to="/addenda" search={{ id: row.original.id } as never} className="text-blue-600 hover:underline">{row.original.addendumNo}</Link>,
    },
    { accessorKey: "changeType", header: "TYPE", cell: ({ row }) => <Badge variant="secondary">{row.original.changeType}</Badge> },
    { accessorKey: "effectiveFrom", header: "EFFECTIVE FROM" },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
  ], []);

  if (q.isLoading) return <div className="text-sm text-muted-foreground">Loading...</div>;
  if (q.isError) return <div className="text-sm text-destructive">Failed to load contract</div>;
  if (!c) return null;

  const readOnly = c.status !== "DRAFT" && c.status !== "REVISION_REQUESTED";
  const pdf = (attQ.data ?? []).find((a) => (a.contentType ?? "").includes("pdf") || a.fileName.toLowerCase().endsWith(".pdf"));
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
            <ApprovalProgressPanel progress={progQ.data} isLoading={progQ.isLoading} error={progQ.error} />
            <DocumentSigningPanel key={`CONTRACT:${c.id}`} documentType="CONTRACT" documentId={c.id} documentStatus={c.status} />
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
            {addQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : addQ.isError ? <div className="space-y-2"><div className="text-sm text-destructive">Failed to load addenda</div>{relatedCursor && <Button variant="outline" size="sm" onClick={recoverRelated}>Return to first page</Button>}</div> : <DataTable columns={addColumns} data={addQ.data?.content ?? []} emptyMessage="No addenda" pageSize={DEFAULT_PAGE_SIZE} serverPagination={{ page: addQ.data?.number ?? relatedPage, totalPages: addQ.data?.totalPages ?? 0, totalItems: addQ.data?.totalElements ?? 0, onPageChange: changeRelatedPage }} />}
          </CardContent>
        </Card>
      )}
      {tab === "approval-history" && <Card><CardHeader><CardTitle className="text-base">Approval History</CardTitle></CardHeader><CardContent><HistoryTimeline history={histQ.data} isLoading={histQ.isLoading} /></CardContent></Card>}
      {tab === "attachments" && <AttachmentPanel ownerType="CONTRACT" ownerId={c.id} />}
    </div>
  );
}
