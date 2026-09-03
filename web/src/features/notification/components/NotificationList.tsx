import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BellOff, CheckCheck } from "lucide-react";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { cn } from "@/shared/lib/cn";
import { inboxQuery } from "../hooks/notificationQueries";
import { notificationApi } from "../services/notificationApi";
import type { NotificationCategory, NotificationResponse } from "../types/notificationTypes";

const PAGE_SIZE = 25;

// Tabs and their counters come from InboxResponse.counts, which is computed
// unfiltered - so a tab shows its total even while another tab is displayed.
// SYSTEM has a counter but no tab in the design; it surfaces under All.
type Tab = { key: string; label: string; countKey: string; unread?: boolean; category?: NotificationCategory };

const TABS: Tab[] = [
  { key: "all", label: "All", countKey: "all" },
  { key: "unread", label: "Unread", countKey: "unread", unread: true },
  { key: "APPROVAL", label: "Approvals", countKey: "APPROVAL", category: "APPROVAL" },
  { key: "ESIGN", label: "E-signature", countKey: "ESIGN", category: "ESIGN" },
  { key: "EXPIRY", label: "Expiring", countKey: "EXPIRY", category: "EXPIRY" },
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
    onSuccess: () => qc.invalidateQueries({ queryKey: ["inbox"] }),
  });

  function selectTab(key: string) {
    setTabKey(key);
    setPage(0);
  }

  if (!canRead) {
    return (
      <Card>
        <CardContent className="p-6 text-sm">
          Need <code>notification:read</code>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>
          Notifications{inbox ? ` (${inbox.unreadCount} unread)` : ""}
        </CardTitle>
        <Button
          size="sm"
          variant="outline"
          disabled={!inbox || inbox.unreadCount === 0 || markAllRead.isPending}
          onClick={() => markAllRead.mutate()}
        >
          <CheckCheck size={15} className="mr-1.5" />
          {markAllRead.isPending ? "Marking..." : "Mark all read"}
        </Button>
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

        {markAllRead.isError && (
          <div className="text-sm text-destructive">
            {getApiErrorMessage(markAllRead.error, "Could not mark all read")}
          </div>
        )}

        {listQ.isLoading ? (
          <div className="text-sm text-muted-foreground">Loading...</div>
        ) : listQ.isError ? (
          <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed to load inbox")}</div>
        ) : items.length === 0 ? (
          <div className="flex flex-col items-center gap-2 py-12 text-muted-foreground">
            <BellOff size={22} />
            <span className="text-sm">Nothing here</span>
          </div>
        ) : (
          <div className="divide-y divide-border">
            {items.map((n) => (
              <NotificationRow key={n.id} n={n} onRead={() => markRead.mutate(n.id)} />
            ))}
          </div>
        )}

        {totalPages > 1 && (
          <div className="flex items-center justify-between text-sm">
            <span className="text-xs text-muted-foreground">Rows per page: {PAGE_SIZE}</span>
            <div className="flex items-center gap-2">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                Previous
              </Button>
              <span className="py-1 text-xs text-muted-foreground">
                Page {page + 1} · {totalPages}
              </span>
              <Button size="sm" variant="outline" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>
                Next
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function NotificationRow({ n, onRead }: { n: NotificationResponse; onRead: () => void }) {
  const unread = n.readAt === null;
  return (
    <div
      className={cn("flex gap-3 px-1 py-3", unread && "bg-primary/[0.03]")}
      onClick={unread ? onRead : undefined}
      role={unread ? "button" : undefined}
      tabIndex={unread ? 0 : undefined}
      onKeyDown={unread ? (e) => { if (e.key === "Enter" || e.key === " ") onRead(); } : undefined}
    >
      <span
        className={cn("mt-2 h-2 w-2 shrink-0 rounded-full", unread ? "bg-primary" : "bg-transparent")}
        aria-label={unread ? "Unread" : undefined}
      />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn("text-sm", unread ? "font-semibold text-foreground" : "font-medium text-foreground/80")}>
            {n.title}
          </span>
          <Badge variant="secondary">{CATEGORY_LABEL[n.category] ?? n.category}</Badge>
          <DocumentRef n={n} />
        </div>
        <p className="mt-0.5 text-sm text-muted-foreground">{n.body}</p>
      </div>
      <time className="shrink-0 text-xs text-muted-foreground tabular-nums" dateTime={n.createdAt} title={n.createdAt}>
        {relativeTime(n.createdAt)}
      </time>
    </div>
  );
}

// Only CONTRACT has a detail route that accepts an id today; the price-list,
// statement and volume routes are still placeholders, so those render as text.
function DocumentRef({ n }: { n: NotificationResponse }) {
  if (!n.documentNo) return null;
  if (n.documentType === "CONTRACT" && n.documentId) {
    return (
      <Link
        to="/contracts"
        search={{ id: n.documentId } as never}
        className="text-xs text-blue-600 hover:underline"
        onClick={(e) => e.stopPropagation()}
      >
        {n.documentNo}
      </Link>
    );
  }
  return <span className="text-xs text-muted-foreground">{n.documentNo}</span>;
}

function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const mins = Math.round((Date.now() - then) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toISOString().slice(0, 10);
}
