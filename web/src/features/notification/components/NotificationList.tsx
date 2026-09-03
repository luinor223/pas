import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BellOff, CheckCheck, RefreshCw } from "lucide-react";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Forbidden } from "@/shared/components/Forbidden";
import { Badge } from "@/shared/components/badge";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { cn } from "@/shared/lib/cn";
import { formatDateTime, formatRelative } from "@/shared/lib/format";
import { PaginationControls } from "@/shared/components/pagination-controls";
import { inboxQuery } from "../hooks/notificationQueries";
import { notificationApi } from "../services/notificationApi";
import type { NotificationCategory, NotificationResponse } from "../types/notificationTypes";

const PAGE_SIZE = DEFAULT_PAGE_SIZE;

// Tabs and their counters come from InboxResponse.counts, which is computed
// unfiltered - so a tab shows its total even while another tab is displayed.
type Tab = { key: string; label: string; countKey: string; unread?: boolean; category?: NotificationCategory };

const TABS: Tab[] = [
  { key: "all", label: "All", countKey: "all" },
  { key: "unread", label: "Unread", countKey: "unread", unread: true },
  { key: "APPROVAL", label: "Approvals", countKey: "APPROVAL", category: "APPROVAL" },
  { key: "ESIGN", label: "E-signature", countKey: "ESIGN", category: "ESIGN" },
  { key: "EXPIRY", label: "Expiring", countKey: "EXPIRY", category: "EXPIRY" },
  { key: "SYSTEM", label: "System", countKey: "SYSTEM", category: "SYSTEM" },
];

const CATEGORY_LABEL: Record<NotificationCategory, string> = {
  APPROVAL: "Approval",
  ESIGN: "E-signature",
  EXPIRY: "Expiring",
  SYSTEM: "System",
};

export function NotificationList() {
  const qc = useQueryClient();
  const canRead = useHasPermission("notification:read");
  const [tabKey, setTabKey] = useState("all");
  const [page, setPage] = useState(0);

  const tab = TABS.find((t) => t.key === tabKey) ?? TABS[0];
  // enabled: the early Forbidden return below does not stop the hook, so an
  // unauthorized user would otherwise fire a 403 on every render.
  const listQ = useQuery({
    ...inboxQuery({
      unread: tab.unread || undefined,
      category: tab.category,
      page,
      size: PAGE_SIZE,
      sort: "createdAt,desc",
    }),
    enabled: canRead,
    // Keep the newest page current without making every historical page poll.
    refetchInterval: canRead && page === 0 ? 30_000 : false,
    refetchIntervalInBackground: false,
  });

  const inbox = listQ.data;
  const items = inbox?.items ?? [];
  const totalPages = Math.max(1, Math.ceil((inbox?.total ?? 0) / PAGE_SIZE));


  const markRead = useMutation({
    mutationFn: (id: string) => notificationApi.markRead(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["inbox"] }),
  });
  const markAllRead = useMutation({
    mutationFn: () => notificationApi.markAllRead(),
    onSuccess: () => {
      // The unread tab empties, so any page past the first no longer exists.
      setPage(0);
      qc.invalidateQueries({ queryKey: ["inbox"] });
    },
  });

  function selectTab(key: string) {
    setTabKey(key);
    setPage(0);
  }

  if (!canRead) {
    return <Forbidden message="You do not have access to notifications. An administrator can grant it." />;
  }

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3">
        <div>
          <CardTitle>
            Notifications{inbox ? ` (${inbox.unreadCount} unread)` : ""}
          </CardTitle>
          <p className="mt-1 text-xs text-muted-foreground">
            {page === 0
              ? "Automatically checks for new notifications every 30 seconds."
              : "Refresh to check this page for updates."}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={listQ.isFetching}
            onClick={() => listQ.refetch()}
          >
            <RefreshCw size={14} className={listQ.isFetching ? "mr-1.5 animate-spin" : "mr-1.5"} />
            {listQ.isFetching ? "Checking..." : "Refresh"}
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={!inbox || inbox.unreadCount === 0 || markAllRead.isPending}
            onClick={() => markAllRead.mutate()}
          >
            <CheckCheck size={15} className="mr-1.5" />
            {markAllRead.isPending ? "Marking..." : "Mark all read"}
          </Button>
        </div>
      </CardHeader>

      <CardContent className="space-y-3">
        <div className="flex flex-wrap gap-1 border-b border-border">
          {TABS.map((t) => {
            const active = t.key === tab.key;
            const count = inbox?.counts?.[t.countKey];
            return (
              <button
                key={t.key}
                type="button"
                onClick={() => selectTab(t.key)}
                className={cn(
                  "-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors",
                  active
                    ? "border-primary text-primary"
                    : "border-transparent text-muted-foreground hover:text-foreground"
                )}
              >
                {t.label}
                {count !== undefined && count > 0 && (
                  <span className="ml-1.5 rounded-full bg-muted px-1.5 py-0.5 text-xs tabular-nums text-muted-foreground">
                    {count}
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {(markAllRead.isError || markRead.isError) && (
          <div className="text-sm text-destructive">
            {getApiErrorMessage(
              markAllRead.error ?? markRead.error,
              "Could not update the notification"
            )}
          </div>
        )}

        {listQ.isLoading ? (
          <div className="text-sm text-muted-foreground">Loading...</div>
        ) : listQ.isError ? (
          <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed to load inbox")}</div>
        ) : items.length === 0 ? (
          <div className="flex flex-col items-center gap-2 py-12 text-muted-foreground">
            <BellOff size={22} />
            <span className="text-sm">
              {tab.unread ? "You are all caught up" : `No ${tab.label.toLowerCase()} notifications`}
            </span>
          </div>
        ) : (
          <div className="divide-y divide-border">
            {items.map((n) => (
              <NotificationRow key={n.id} n={n} onOpen={() => markRead.mutate(n.id)} />
            ))}
          </div>
        )}

        {inbox && (
          <PaginationControls
            page={page}
            totalPages={totalPages}
            pageSize={PAGE_SIZE}
            totalItems={inbox.total}
            onPageChange={setPage}
          />
        )}
      </CardContent>
    </Card>
  );
}

type DocumentTarget = {
  to: "/contracts" | "/price-lists" | "/volume-records";
  search: Record<string, string>;
};

function documentTarget(n: NotificationResponse): DocumentTarget | undefined {
  if (!n.documentId || !n.documentType) return undefined;
  if (n.documentType === "CONTRACT") return { to: "/contracts", search: { id: n.documentId } };
  if (n.documentType === "PRICE_LIST") return { to: "/price-lists", search: { versionId: n.documentId } };
  if (n.documentType === "OPERATION_PERIOD") return { to: "/volume-records", search: { tab: "periods" } };
  return undefined;
}

function NotificationRow({ n, onOpen }: { n: NotificationResponse; onOpen: () => void }) {
  const unread = n.readAt === null;
  const target = documentTarget(n);
  const linked = Boolean(target);
  const handleOpen = () => { if (unread) onOpen(); };

  const content = (
    <>
      <span
        className={cn("mt-2 h-2 w-2 shrink-0 rounded-full", unread ? "bg-primary" : "bg-transparent")}
        aria-hidden="true"
      />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn("text-sm", unread ? "font-semibold text-foreground" : "font-medium text-foreground/80")}>
            {n.title}
          </span>
          <Badge variant="secondary">{CATEGORY_LABEL[n.category] ?? n.category}</Badge>
          {n.documentNo && (
            <span className={cn("text-xs", linked ? "text-primary" : "text-muted-foreground")}>{n.documentNo}</span>
          )}
        </div>
        <p className="mt-0.5 text-sm text-muted-foreground">{n.body}</p>
      </div>
      <time
        className="shrink-0 text-xs text-muted-foreground tabular-nums"
        dateTime={n.createdAt}
        title={formatDateTime(n.createdAt)}
      >
        {formatRelative(n.createdAt)}
      </time>
      {unread && <span className="sr-only">Unread</span>}
    </>
  );

  const rowClassName = cn(
    "flex w-full gap-3 rounded-sm px-1 py-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset",
    unread && "bg-primary/[0.03]",
    (linked || unread) && "hover:bg-muted/50",
  );

  if (target) {
    return (
      <Link to={target.to} search={target.search as never} className={rowClassName} onClick={handleOpen}>
        {content}
      </Link>
    );
  }

  if (unread) {
    return <button type="button" className={rowClassName} onClick={handleOpen}>{content}</button>;
  }

  return <div className={rowClassName}>{content}</div>;
}
