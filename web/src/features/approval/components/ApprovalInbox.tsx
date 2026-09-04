import { useCallback, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { Check, Download, RefreshCw, RotateCcw, X } from "lucide-react";
import { Badge } from "@/shared/components/badge";
import { Button } from "@/shared/components/button";
import { Card, CardContent } from "@/shared/components/card";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { ClearFiltersButton } from "@/shared/components/clear-filters-button";
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
import { downloadCsv } from "@/shared/lib/csv";
import { documentTarget } from "@/shared/lib/document-links";
import { uuid } from "@/shared/lib/uuid";
import { useRecoverOutOfRangePage } from "@/shared/hooks/use-recover-out-of-range-page";
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

type ActionDialogState = { item: ApprovalInboxItem; action: ApprovalAction } | null;

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
  const [actionDialog, setActionDialog] = useState<ActionDialogState>(null);
  const [announcement, setAnnouncement] = useState("");
  const [actionKey, setActionKey] = useState("");

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
  const assignedCountQuery = useQuery({
    ...approvalInboxQuery("ASSIGNED", { page: 0, size: 1 }),
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });

  const actionMutation = useMutation({
    mutationFn: ({ item, action, idempotencyKey, comment }: { item: ApprovalInboxItem; action: ApprovalAction; idempotencyKey: string; comment?: string }) => {
      if (!item.stepInstanceId) throw new Error("This approval task is no longer actionable.");
      return approvalApi.act(item.stepInstanceId, action, idempotencyKey, comment);
    },
    onSuccess: (_data, variables) => {
      setAnnouncement(`${variables.item.documentNo} ${actionPastTense(variables.action)}.`);
      setActionDialog(null);
      queryClient.invalidateQueries({ queryKey: ["approval-inbox"] });
    },
  });

  const totalPages = Math.max(1, activeQuery.data?.totalPages ?? 1);
  const totalItems = activeQuery.data?.totalItems ?? 0;

  const recoverFirstPage = useCallback(() => navigate({ search: (previous) => ({ ...previous, page: undefined }), replace: true }), [navigate]);
  useRecoverOutOfRangePage({ ready: activeQuery.isSuccess, page, totalPages: activeQuery.data?.totalPages ?? 0, totalItems, recover: recoverFirstPage });

  function changeTab(next: ApprovalTab) {
    navigate({ search: (previous) => ({ ...previous, tab: next === "ASSIGNED" ? undefined : next, page: undefined }) });
  }

  function clearFilters() {
    navigate({ search: (previous) => ({ ...previous, q: undefined, documentType: undefined, priority: undefined, page: undefined }), replace: true });
  }

  function openActionDialog(item: ApprovalInboxItem, action: ApprovalAction) {
    actionMutation.reset();
    setActionKey(uuid());
    setActionDialog({ item, action });
  }

  function closeActionDialog() {
    if (actionMutation.isPending) return;
    actionMutation.reset();
    setActionDialog(null);
  }

  function exportVisibleItems() {
    const header = ["Document", "Customer", "Type", "Workflow step", "Priority", "Status", "Submitted by", "Created at"];
    const rows = items.map((item) => [
      item.documentNo, item.customerName ?? "", documentTypeLabel(item.documentTypeCode), item.currentStepName ?? "",
      humanize(item.priority), humanize(item.status), item.requestedByName ?? "", item.createdAt,
    ]);
    downloadCsv(`approvals-${tab.toLowerCase()}.csv`, [header, ...rows]);
  }

  const hasFilters = Boolean(search || documentType || priority);

  return (
    <div className="space-y-4">
      <TabBar
        tabs={TABS.map((item) => ({ ...item, count: item.value === "ASSIGNED" ? assignedCountQuery.data?.totalItems : item.value === tab ? activeQuery.data?.totalItems : undefined }))}
        value={tab}
        onChange={changeTab}
        panelId="approval-inbox-panel"
      />

      <div id="approval-inbox-panel" role="tabpanel" className="space-y-4">
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
        <ClearFiltersButton disabled={!hasFilters} onClick={clearFilters} />
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

      <div role="status" aria-live="polite" className="sr-only">{announcement}</div>

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
                      <div>{item.currentStepName || "—"}</div>
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
                            title={!canAct ? "You do not have permission to act on approvals" : undefined}
                            onClick={() => openActionDialog(item, "APPROVE")}
                          >
                            <Check size={14} className="mr-1" /> Approve
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            className="border-st-review text-st-review hover:bg-st-review-bg"
                            disabled={!canAct || !item.stepInstanceId || actionMutation.isPending}
                            title={!canAct ? "You do not have permission to act on approvals" : undefined}
                            onClick={() => openActionDialog(item, "REQUEST_REVISION")}
                          >
                            <RotateCcw size={14} className="mr-1" /> Revise
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            className="border-destructive text-destructive hover:bg-destructive/5"
                            disabled={!canAct || !item.stepInstanceId || actionMutation.isPending}
                            title={!canAct ? "You do not have permission to act on approvals" : undefined}
                            onClick={() => openActionDialog(item, "REJECT")}
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
      </div>

      <ConfirmDialog
        key={actionDialog ? `${actionDialog.item.stepInstanceId}-${actionDialog.action}` : "closed"}
        open={Boolean(actionDialog)}
        title={actionDialogTitle(actionDialog)}
        body={actionDialogBody(actionDialog)}
        confirmLabel={actionDialogConfirmLabel(actionDialog?.action)}
        pendingLabel={actionDialog?.action === "APPROVE" ? "Approving..." : "Saving..."}
        pending={actionMutation.isPending}
        error={actionDialog && actionMutation.isError ? actionMutation.error : undefined}
        cancelLabel={actionDialog?.action === "APPROVE" ? "Keep reviewing" : "Cancel"}
        confirmVariant={actionDialog?.action === "REJECT" ? "destructive" : "default"}
        reason={actionDialog?.action && actionDialog.action !== "APPROVE" ? actionReason(actionDialog.action) : undefined}
        onCancel={closeActionDialog}
        onConfirm={(comment) => {
          if (actionDialog) actionMutation.mutate({ item: actionDialog.item, action: actionDialog.action, idempotencyKey: actionKey, comment });
        }}
      />
    </div>
  );
}

function PriorityBadge({ priority }: { priority: string }) {
  const key = priority.toUpperCase();
  const style = key === "URGENT" || key === "HIGH"
    ? "bg-priority-high-bg text-priority-high"
    : key === "LOW" ? "bg-st-expired-bg text-st-expired" : "bg-st-effective-bg text-st-effective";
  return <Badge className={style}>{humanize(priority)}</Badge>;
}

function documentLink(item: ApprovalInboxItem) {
  const target = documentTarget(item.documentTypeCode, item.documentId);
  return target ? <Link to={target.to} search={target.search as never} className="text-primary hover:underline">{item.documentNo}</Link> : item.documentNo;
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

function actionPastTense(action: ApprovalAction) {
  if (action === "APPROVE") return "approved";
  if (action === "REJECT") return "rejected";
  return "sent for revision";
}

function actionDialogTitle(dialog: ActionDialogState) {
  if (!dialog) return "Review request";
  if (dialog.action === "APPROVE") return `Approve ${dialog.item.documentNo}?`;
  return dialog.action === "REQUEST_REVISION" ? "Request revision" : "Reject request";
}

function actionDialogBody(dialog: ActionDialogState) {
  if (!dialog) return null;
  const context = `${dialog.item.customerName || documentTypeLabel(dialog.item.documentTypeCode)}${dialog.item.currentStepName ? ` · ${dialog.item.currentStepName}` : ""}`;
  if (dialog.action !== "APPROVE") return `${dialog.item.documentNo} · ${context}`;
  return <div className="space-y-2"><p className="text-muted-foreground">{context}</p><p>This records your approval and moves the document to its next workflow step. This action cannot be undone here.</p></div>;
}

function actionDialogConfirmLabel(action?: ApprovalAction) {
  if (action === "APPROVE") return "Approve request";
  return action === "REQUEST_REVISION" ? "Send for revision" : "Reject request";
}

function actionReason(action: Exclude<ApprovalAction, "APPROVE">) {
  return {
    label: action === "REQUEST_REVISION" ? "Reason for revision" : "Reason for rejection",
    placeholder: action === "REQUEST_REVISION" ? "Explain what needs to change before this can be approved..." : "Explain why this request is being rejected...",
    required: true,
    description: "Required — this note is shown to the submitter and recorded in the activity history.",
  };
}
