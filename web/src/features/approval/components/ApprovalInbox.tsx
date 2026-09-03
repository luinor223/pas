import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Check, Download, RefreshCw, RotateCcw, X } from "lucide-react";
import { Badge } from "@/shared/components/badge";
import { Button } from "@/shared/components/button";
import { Card, CardContent } from "@/shared/components/card";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Label } from "@/shared/components/label";
import { PaginationControls } from "@/shared/components/pagination-controls";
import { SearchInput } from "@/shared/components/search-input";
import { Select } from "@/shared/components/select";
import { StatusBadge } from "@/shared/components/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import { Textarea } from "@/shared/components/textarea";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { cn } from "@/shared/lib/cn";
import { formatDateTime, formatRelative } from "@/shared/lib/format";
import { humanize } from "@/shared/lib/text";
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
  const [tab, setTab] = useState<ApprovalTab>("ASSIGNED");
  const [search, setSearch] = useState("");
  const [documentType, setDocumentType] = useState("");
  const [priority, setPriority] = useState("");
  const [page, setPage] = useState(0);
  const [reasonDialog, setReasonDialog] = useState<ReasonDialogState>(null);

  const assignedQuery = useQuery({
    ...approvalInboxQuery("ASSIGNED"),
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });
  const submittedQuery = useQuery(approvalInboxQuery("SUBMITTED"));
  const completedQuery = useQuery(approvalInboxQuery("COMPLETED"));
  const queries = { ASSIGNED: assignedQuery, SUBMITTED: submittedQuery, COMPLETED: completedQuery };
  const activeQuery = queries[tab];
  const items = activeQuery.data?.items ?? [];

  const actionMutation = useMutation({
    mutationFn: ({ item, action, comment }: { item: ApprovalInboxItem; action: ApprovalAction; comment?: string }) => {
      if (!item.stepInstanceId) throw new Error("This approval task is no longer actionable.");
      return approvalApi.act(item.stepInstanceId, action, comment);
    },
    onSuccess: () => {
      setReasonDialog(null);
      queryClient.invalidateQueries({ queryKey: ["approval-inbox"] });
    },
  });

  const needle = search.trim().toLowerCase();
  const filtered = items.filter((item) => {
    const matchesSearch = !needle || [item.documentNo, item.customerName, item.currentStepName, item.requestedByName]
      .some((value) => value?.toLowerCase().includes(needle));
    return matchesSearch
      && (!documentType || item.documentTypeCode === documentType)
      && (!priority || item.priority === priority);
  });

  const totalPages = Math.max(1, Math.ceil(filtered.length / DEFAULT_PAGE_SIZE));
  const currentPage = Math.min(page, totalPages - 1);
  const visible = filtered.slice(currentPage * DEFAULT_PAGE_SIZE, (currentPage + 1) * DEFAULT_PAGE_SIZE);

  function changeTab(next: ApprovalTab) {
    setTab(next);
    setPage(0);
  }

  function clearFilters() {
    setSearch("");
    setDocumentType("");
    setPriority("");
    setPage(0);
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
    const rows = filtered.map((item) => [
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
      <div className="flex border-b border-border">
        {TABS.map(({ value, label }) => {
          const count = queries[value].data?.items.length;
          return (
            <button
              key={value}
              type="button"
              onClick={() => changeTab(value)}
              className={cn(
                "-mb-px flex items-center gap-2 border-b-2 px-4 py-3 text-sm font-medium transition-colors",
                tab === value ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground",
              )}
            >
              {label}
              {count !== undefined && <Badge variant={tab === value ? "default" : "secondary"}>{count}</Badge>}
            </button>
          );
        })}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <SearchInput
          className="w-full sm:w-60"
          label="Search approvals"
          placeholder="Search document or customer"
          value={search}
          onChange={(value) => { setSearch(value); setPage(0); }}
        />
        <Select
          className="w-full sm:w-48"
          aria-label="Filter by document type"
          value={documentType}
          onChange={(event) => { setDocumentType(event.target.value); setPage(0); }}
        >
          <option value="">Document type: All</option>
          {Object.entries(DOCUMENT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </Select>
        <Select
          className="w-full sm:w-40"
          aria-label="Filter by priority"
          value={priority}
          onChange={(event) => { setPriority(event.target.value); setPage(0); }}
        >
          <option value="">Priority: All</option>
          {['LOW', 'NORMAL', 'HIGH', 'URGENT'].map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
        </Select>
        {hasFilters && <Button variant="ghost" size="sm" onClick={clearFilters}>Clear filters</Button>}
        <span className="ml-auto text-xs text-muted-foreground">
          {tab === "ASSIGNED" ? `${filtered.length} awaiting action` : `${filtered.length} records`}
        </span>
        <Button variant="outline" size="sm" onClick={exportVisibleItems} disabled={filtered.length === 0}>
          <Download size={14} className="mr-1.5" /> Export
        </Button>
        <Button variant="outline" size="sm" onClick={() => activeQuery.refetch()} disabled={activeQuery.isFetching}>
          <RefreshCw size={14} className={cn("mr-1.5", activeQuery.isFetching && "animate-spin")} /> Refresh
        </Button>
      </div>

      {actionMutation.isError && !reasonDialog && (
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
          ) : visible.length === 0 ? (
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
                {visible.map((item) => (
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
                            className="bg-emerald-600 hover:bg-emerald-700"
                            disabled={!canAct || !item.stepInstanceId || actionMutation.isPending}
                            onClick={() => actionMutation.mutate({ item, action: "APPROVE" })}
                          >
                            <Check size={14} className="mr-1" /> Approve
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            className="border-amber-500 text-amber-700 hover:bg-amber-50"
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

      {!activeQuery.isLoading && !activeQuery.isError && filtered.length > 0 && (
        <PaginationControls
          page={currentPage}
          totalPages={totalPages}
          pageSize={DEFAULT_PAGE_SIZE}
          totalItems={filtered.length}
          onPageChange={setPage}
        />
      )}

      <ReasonDialog
        key={reasonDialog ? `${reasonDialog.item.stepInstanceId}-${reasonDialog.action}` : "closed"}
        state={reasonDialog}
        pending={actionMutation.isPending}
        error={reasonDialog && actionMutation.isError ? actionMutation.error : undefined}
        onClose={closeReasonDialog}
        onSubmit={(comment) => {
          if (reasonDialog) actionMutation.mutate({ item: reasonDialog.item, action: reasonDialog.action, comment });
        }}
      />
    </div>
  );
}

function ReasonDialog({
  state, pending, error, onClose, onSubmit,
}: {
  state: ReasonDialogState;
  pending: boolean;
  error?: unknown;
  onClose: () => void;
  onSubmit: (comment: string) => void;
}) {
  const [comment, setComment] = useState("");
  if (!state) return null;

  const revision = state.action === "REQUEST_REVISION";
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{revision ? "Request revision" : "Reject request"}</DialogTitle>
          <p className="text-xs text-muted-foreground">
            {state.item.documentNo} · {state.item.customerName || documentTypeLabel(state.item.documentTypeCode)}
            {state.item.currentStepName ? ` · currently at ${state.item.currentStepName}` : ""}
          </p>
        </DialogHeader>
        <div>
          <Label>{revision ? "Reason for revision" : "Reason for rejection"} *</Label>
          <Textarea
            autoFocus
            required
            aria-required="true"
            rows={4}
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            placeholder={revision ? "Explain what needs to change before this can be approved..." : "Explain why this request is being rejected..."}
          />
          <p className="mt-2 text-xs text-muted-foreground">Required — this note is shown to the submitter and recorded in the activity history.</p>
          {error != null && <p role="alert" className="mt-2 text-sm text-destructive">{getApiErrorMessage(error, "Could not update this approval")}</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={pending} onClick={onClose}>Cancel</Button>
          <Button
            variant={revision ? "default" : "destructive"}
            className={revision ? "bg-amber-600 hover:bg-amber-700" : undefined}
            disabled={pending || !comment.trim()}
            onClick={() => onSubmit(comment.trim())}
          >
            {pending ? "Saving..." : revision ? "Send for revision" : "Reject request"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function PriorityBadge({ priority }: { priority: string }) {
  const key = priority.toUpperCase();
  const style = key === "URGENT" || key === "HIGH"
    ? "bg-red-100 text-red-700"
    : key === "LOW" ? "bg-slate-100 text-slate-600" : "bg-blue-50 text-blue-700";
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
  return `"${value.replaceAll('"', '""')}"`;
}
