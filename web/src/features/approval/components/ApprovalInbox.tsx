import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { Check, Download, RefreshCw, RotateCcw, X } from "lucide-react";
import { Badge } from "@/shared/components/badge";
import { Button } from "@/shared/components/button";
import { Card, CardContent } from "@/shared/components/card";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { PaginationControls } from "@/shared/components/pagination-controls";
import { SearchInput } from "@/shared/components/search-input";
import { Select } from "@/shared/components/select";
import { StatusBadge } from "@/shared/components/status-badge";
import { TabBar } from "@/shared/components/tab-bar";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { cn } from "@/shared/lib/cn";
import { formatDateTime, formatRelative } from "@/shared/lib/format";
import { humanize } from "@/shared/lib/text";
import { useDebouncedSearch } from "@/shared/lib/use-debounced-search";
import { approvalInboxQuery } from "../hooks/approvalQueries";
import { approvalApi } from "../services/approvalApi";
import type { ApprovalAction, ApprovalInboxItem, ApprovalTab } from "../types/approvalTypes";

const TABS: { value: ApprovalTab; label: string }[] = [
  { value: "ASSIGNED", label: "Assigned to me" },
  { value: "SUBMITTED", label: "Submitted by me" },
  { value: "COMPLETED", label: "Completed" },
];

const DOCUMENT_LABELS: Record<string, string> = {
  CONTRACT: "Contract",
  ADDENDUM: "Addendum",
  PRICE_LIST: "Price list",
  PAYMENT_STATEMENT: "Payment statement",
};

type ReasonAction = Exclude<ApprovalAction, "APPROVE">;
type ReasonDialogState = { item: ApprovalInboxItem; action: ReasonAction } | null;

export function ApprovalInbox() {
  const queryClient = useQueryClient();
  const canAct = useHasPermission("approval:act");
  const navigate = useNavigate({ from: "/approvals" });
  const routeSearch = useSearch({ from: "/approvals" });
  const tab = (routeSearch.tab ?? "ASSIGNED") as ApprovalTab;
  const search = routeSearch.q ?? "";
  const documentType = routeSearch.documentType ?? "";
  const priority = routeSearch.priority ?? "";
  const page = routeSearch.page ?? 0;
  const debouncedSearch = useDebouncedSearch(search);
  const [reasonDialog, setReasonDialog] = useState<ReasonDialogState>(null);
  const [approveTarget, setApproveTarget] = useState<ApprovalInboxItem | null>(null);

  const activeQuery = useQuery({
    ...approvalInboxQuery(tab, {
      page,
      size: DEFAULT_PAGE_SIZE,
      q: debouncedSearch || undefined,
      documentType: documentType || undefined,
      priority: priority || undefined,
    }),
    refetchInterval: tab === "ASSIGNED" ? 30_000 : false,
    refetchIntervalInBackground: false,
  });
  const items = activeQuery.data?.items ?? [];

  const actionMutation = useMutation({
    mutationFn: ({ item, action, comment }: { item: ApprovalInboxItem; action: ApprovalAction; comment?: string }) => {
      if (!item.stepInstanceId) throw new Error("This approval task is no longer actionable.");
      return approvalApi.act(item.stepInstanceId, action, comment);
    },
    onSuccess: () => {
      setReasonDialog(null);
      setApproveTarget(null);
      queryClient.invalidateQueries({ queryKey: ["approval-inbox"] });
    },
  });

  const totalPages = Math.max(1, activeQuery.data?.totalPages ?? 1);
  const totalItems = activeQuery.data?.totalItems ?? 0;

  function changeTab(next: ApprovalTab) {
    navigate({ search: (previous) => ({ ...previous, tab: next === "ASSIGNED" ? undefined : next, page: undefined }) });
  }

  function clearFilters() {
    navigate({ search: (previous) => ({ ...previous, q: undefined, documentType: undefined, priority: undefined, page: undefined }), replace: true });
  }

  function openReasonDialog(item: ApprovalInboxItem, action: ReasonAction) {
    actionMutation.reset();
    setReasonDialog({ item, action });
  }

  function closeReasonDialog() {
    if (actionMutation.isPending) return;
    actionMutation.reset();
    setReasonDialog(null);
  }

  function exportVisibleItems() {
    const header = ["Document", "Customer", "Type", "Workflow step", "Priority", "Status", "Submitted by", "Created at"];
    const rows = items.map((item) => [
      item.documentNo, item.customerName ?? "", documentTypeLabel(item.documentTypeCode), item.currentStepName ?? "",
      humanize(item.priority), humanize(item.status), item.requestedByName ?? "", item.createdAt,
    ]);
    const csv = [header, ...rows].map((row) => row.map(csvCell).join(",")).join("\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `approvals-${tab.toLowerCase()}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  const hasFilters = Boolean(search || documentType || priority);

  return (
    <div className="space-y-4">
      <TabBar
        tabs={TABS.map((item) => ({ ...item, count: item.value === tab ? activeQuery.data?.totalItems : undefined }))}
        value={tab}
        onChange={changeTab}
      />

      <div className="flex flex-wrap items-center gap-2">
        <SearchInput
          className="w-full sm:w-60"
          label="Search approvals"
          placeholder="Search document or customer"
          value={search}
          onChange={(value) => navigate({ search: (previous) => ({ ...previous, q: value || undefined, page: undefined }), replace: true })}
        />
        <Select
          className="w-full sm:w-48"
          aria-label="Filter by document type"
          value={documentType}
          onChange={(event) => navigate({ search: (previous) => ({ ...previous, documentType: event.target.value || undefined, page: undefined }), replace: true })}
        >
          <option value="">Document type: All</option>
          {Object.entries(DOCUMENT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </Select>
        <Select
          className="w-full sm:w-40"
          aria-label="Filter by priority"
          value={priority}
          onChange={(event) => navigate({ search: (previous) => ({ ...previous, priority: event.target.value || undefined, page: undefined }), replace: true })}
        >
          <option value="">Priority: All</option>
          {['LOW', 'NORMAL', 'HIGH', 'URGENT'].map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
        </Select>
        {hasFilters && <Button variant="ghost" size="sm" onClick={clearFilters}>Clear filters</Button>}
        <span className="ml-auto text-xs text-muted-foreground">
          {tab === "ASSIGNED" ? `${totalItems} awaiting action` : `${totalItems} records`}
        </span>
        <Button variant="outline" size="sm" onClick={exportVisibleItems} disabled={items.length === 0}>
          <Download size={14} className="mr-1.5" /> Export page
        </Button>
        <Button variant="outline" size="sm" onClick={() => activeQuery.refetch()} disabled={activeQuery.isFetching}>
          <RefreshCw size={14} className={cn("mr-1.5", activeQuery.isFetching && "animate-spin")} /> Refresh
        </Button>
      </div>

      {actionMutation.isError && !reasonDialog && !approveTarget && (
        <div role="alert" className="text-sm text-destructive">
          {getApiErrorMessage(actionMutation.error, "Could not update this approval. Refresh and try again.")}
        </div>
      )}

      <Card>
        <CardContent className="p-0">
          {activeQuery.isLoading ? (
            <p className="p-6 text-sm text-muted-foreground">Loading approvals...</p>
          ) : activeQuery.isError ? (
            <p className="p-6 text-sm text-destructive">{getApiErrorMessage(activeQuery.error, "Could not load approvals")}</p>
          ) : items.length === 0 ? (
            <div className="p-12 text-center">
              <p className="text-sm font-medium">{hasFilters ? "No approvals match your filters" : emptyMessage(tab)}</p>
              <p className="mt-1 text-xs text-muted-foreground">{hasFilters ? "Try changing or clearing the filters." : emptyHelp(tab)}</p>
            </div>
          ) : (
            <Table className="min-w-[980px]">
              <TableHeader>
                <TableRow>
                  <TableHead>DOCUMENT</TableHead>
                  <TableHead>CUSTOMER</TableHead>
                  <TableHead>TYPE</TableHead>
                  <TableHead>WORKFLOW STEP</TableHead>
                  <TableHead>{tab === "ASSIGNED" ? "WAITING" : "CREATED"}</TableHead>
                  <TableHead>PRIORITY</TableHead>
                  {tab !== "ASSIGNED" && <TableHead>STATUS</TableHead>}
                  {tab === "ASSIGNED" && <TableHead className="text-right">ACTIONS</TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((item) => (
                  <TableRow key={`${item.instanceId}-${item.stepInstanceId ?? item.currentStepOrder}`}>
                    <TableCell className="font-medium">{documentLink(item)}</TableCell>
                    <TableCell className="font-medium">{item.customerName || "—"}</TableCell>
                    <TableCell>{documentTypeLabel(item.documentTypeCode)}</TableCell>
                    <TableCell>
                      <div>{item.currentStepName || "Completed"}</div>
                      {item.currentStepRole && <div className="text-xs text-muted-foreground">{humanize(item.currentStepRole)}</div>}
                    </TableCell>
                    <TableCell className="whitespace-nowrap" title={formatDateTime(item.stepActivatedAt ?? item.createdAt)}>
                      {tab === "ASSIGNED" ? elapsed(item.stepActivatedAt ?? item.createdAt) : formatRelative(item.createdAt)}
                    </TableCell>
                    <TableCell><PriorityBadge priority={item.priority} /></TableCell>
                    {tab !== "ASSIGNED" && <TableCell><StatusBadge status={item.status} /></TableCell>}
                    {tab === "ASSIGNED" && (
                      <TableCell>
                        <div className="flex justify-end gap-1.5">
                          <Button
                            size="sm"
                            disabled={!canAct || !item.stepInstanceId || actionMutation.isPending}
                            onClick={() => { actionMutation.reset(); setApproveTarget(item); }}
                          >
                            <Check size={14} className="mr-1" /> Approve
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            className="border-st-review text-st-review hover:bg-st-review-bg"
                            disabled={!canAct || !item.stepInstanceId || actionMutation.isPending}
                            onClick={() => openReasonDialog(item, "REQUEST_REVISION")}
                          >
                            <RotateCcw size={14} className="mr-1" /> Revise
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            className="border-destructive text-destructive hover:bg-destructive/5"
                            disabled={!canAct || !item.stepInstanceId || actionMutation.isPending}
                            onClick={() => openReasonDialog(item, "REJECT")}
                          >
                            <X size={14} className="mr-1" /> Reject
                          </Button>
                        </div>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {!activeQuery.isLoading && !activeQuery.isError && (activeQuery.data?.totalPages ?? 0) > 0 && (
        <PaginationControls
          page={page}
          totalPages={totalPages}
          pageSize={DEFAULT_PAGE_SIZE}
          totalItems={totalItems}
          onPageChange={(nextPage) => navigate({ search: (previous) => ({ ...previous, page: nextPage || undefined }), replace: true })}
        />
      )}

      <ConfirmDialog
        key={reasonDialog ? `${reasonDialog.item.stepInstanceId}-${reasonDialog.action}` : "closed"}
        open={Boolean(reasonDialog)}
        title={reasonDialog?.action === "REQUEST_REVISION" ? "Request revision" : "Reject request"}
        body={reasonDialog ? `${reasonDialog.item.documentNo} · ${reasonDialog.item.customerName || documentTypeLabel(reasonDialog.item.documentTypeCode)}${reasonDialog.item.currentStepName ? ` · currently at ${reasonDialog.item.currentStepName}` : ""}` : ""}
        confirmLabel={reasonDialog?.action === "REQUEST_REVISION" ? "Send for revision" : "Reject request"}
        pendingLabel="Saving..."
        pending={actionMutation.isPending}
        error={reasonDialog && actionMutation.isError ? actionMutation.error : undefined}
        cancelLabel="Cancel"
        confirmVariant={reasonDialog?.action === "REQUEST_REVISION" ? "default" : "destructive"}
        reason={{
          label: reasonDialog?.action === "REQUEST_REVISION" ? "Reason for revision" : "Reason for rejection",
          placeholder: reasonDialog?.action === "REQUEST_REVISION" ? "Explain what needs to change before this can be approved..." : "Explain why this request is being rejected...",
          required: true,
          description: "Required — this note is shown to the submitter and recorded in the activity history.",
        }}
        onCancel={closeReasonDialog}
        onConfirm={(comment) => {
          if (reasonDialog) actionMutation.mutate({ item: reasonDialog.item, action: reasonDialog.action, comment });
        }}
      />
      <ConfirmDialog
        open={Boolean(approveTarget)}
        title={`Approve ${approveTarget?.documentNo ?? "this request"}?`}
        body={approveTarget ? (
          <div className="space-y-2">
            <p className="text-muted-foreground">{approveTarget.customerName || documentTypeLabel(approveTarget.documentTypeCode)}{approveTarget.currentStepName ? ` · ${approveTarget.currentStepName}` : ""}</p>
            <p>This records your approval and moves the document to its next workflow step. This action cannot be undone here.</p>
          </div>
        ) : null}
        confirmLabel="Approve request"
        pendingLabel="Approving..."
        confirmVariant="default"
        pending={actionMutation.isPending}
        error={approveTarget && actionMutation.isError ? actionMutation.error : undefined}
        cancelLabel="Keep reviewing"
        onCancel={() => { if (!actionMutation.isPending) { actionMutation.reset(); setApproveTarget(null); } }}
        onConfirm={() => { if (approveTarget) actionMutation.mutate({ item: approveTarget, action: "APPROVE" }); }}
      />
    </div>
  );
}

function PriorityBadge({ priority }: { priority: string }) {
  const key = priority.toUpperCase();
  const style = key === "URGENT" || key === "HIGH"
    ? "bg-st-rejected-bg text-st-rejected"
    : key === "LOW" ? "bg-st-expired-bg text-st-expired" : "bg-st-effective-bg text-st-effective";
  return <Badge className={style}>{humanize(priority)}</Badge>;
}

function documentLink(item: ApprovalInboxItem) {
  if (item.documentTypeCode === "CONTRACT") {
    return <Link to="/contracts" search={{ id: item.documentId } as never} className="text-primary hover:underline">{item.documentNo}</Link>;
  }
  if (item.documentTypeCode === "PRICE_LIST") {
    return <Link to="/price-lists" search={{ versionId: item.documentId } as never} className="text-primary hover:underline">{item.documentNo}</Link>;
  }
  return item.documentNo;
}

function documentTypeLabel(code: string) {
  return DOCUMENT_LABELS[code] ?? humanize(code);
}

function elapsed(value: string | null) {
  if (!value) return "—";
  const milliseconds = Date.now() - new Date(value).getTime();
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return "—";
  const hours = Math.floor(milliseconds / 3_600_000);
  const days = Math.floor(hours / 24);
  const remainder = hours % 24;
  return days > 0 ? `${days}d ${remainder}h` : hours > 0 ? `${hours}h` : "<1h";
}

function emptyMessage(tab: ApprovalTab) {
  if (tab === "ASSIGNED") return "You have no approvals waiting";
  if (tab === "SUBMITTED") return "You have not submitted any approvals";
  return "You have not completed any approval actions";
}

function emptyHelp(tab: ApprovalTab) {
  return tab === "ASSIGNED" ? "New tasks assigned to you will appear here." : "Records will appear here as workflow activity occurs.";
}

function csvCell(value: string) {
  const safeValue = /^[\t\r ]*[=+\-@]/.test(value) ? `'${value}` : value;
  return `"${safeValue.replaceAll('"', '""')}"`;
}
