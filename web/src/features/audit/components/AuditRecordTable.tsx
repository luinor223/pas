import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight } from "lucide-react";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import { StatusBadge } from "@/shared/components/status-badge";
import { DateRangeFields, isInvalidDateRange } from "@/shared/components/date-range-fields";
import { ClearFiltersButton } from "@/shared/components/clear-filters-button";
import { PaginationControls } from "@/shared/components/pagination-controls";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Forbidden } from "@/shared/components/Forbidden";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { auditRecordsQuery } from "../hooks/auditQueries";
import type { AuditRecordResponse } from "../types/auditTypes";
import { AUDIT_ACTIVITIES, AUDIT_RECORD_TYPES } from "../auditOptions";
import { departmentLabel, permissionLabel, roleLabel } from "@/shared/lib/labels";
import { AUDIT_MODULES, SERVICE_LABELS } from "@/shared/lib/modules";
import { formatDateTime } from "@/shared/lib/format";
import { humanize } from "@/shared/lib/text";

const PAGE_SIZE = 15;

export function AuditRecordTable() {
  const canView = useHasPermission("audit:view_all");
  const [sourceService, setSourceService] = useState("");
  const [entityType, setEntityType] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [action, setAction] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<AuditRecordResponse | null>(null);
  const invalidDateRange = isInvalidDateRange(from, to);
  const hasFilters = !!(sourceService || entityType || searchQuery || action || from || to);

  // enabled: the early Forbidden return below does not stop the hook, so an
  // unauthorized user would otherwise fire a 403 on every render.
  const listQ = useQuery({
    ...auditRecordsQuery({
      sourceService: sourceService || undefined,
      entityType: entityType || undefined,
      query: searchQuery.trim() || undefined,
      action: action || undefined,
      from: toDayBoundary(from, false),
      to: toDayBoundary(to, true),
      page,
      size: PAGE_SIZE,
      sort: "occurredAt,desc",
    }),
    enabled: canView && !invalidDateRange,
    // Audit records arrive asynchronously through Kafka. Refresh the newest
    // page so recently ingested activity appears without a manual reload.
    refetchInterval: canView && !invalidDateRange && page === 0 ? 5_000 : false,
    refetchIntervalInBackground: false,
  });

  const rows = listQ.data?.content ?? [];
  const totalPages = listQ.data?.totalPages ?? 1;
  const visibleRecordTypes = sourceService
    ? AUDIT_RECORD_TYPES.filter((type) => type.module === sourceService)
    : AUDIT_RECORD_TYPES;


  function clear() {
    setSourceService("");
    setEntityType("");
    setSearchQuery("");
    setAction("");
    setFrom("");
    setTo("");
    setPage(0);
  }

  // Server is fail-closed on audit:view_all; gate the page rather than render a 403.
  if (!canView) return <Forbidden message="You do not have access to the activity history. An administrator can grant it." />;

  return (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle>Audit Log ({listQ.data?.totalElements ?? 0})</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="grid min-w-0 grid-cols-1 items-end gap-3 md:grid-cols-2 xl:grid-cols-4">
          <div className="min-w-0">
            <Label>Search</Label>
            <Input placeholder="Record, username or person" value={searchQuery} onChange={(e) => { setSearchQuery(e.target.value); setPage(0); }} />
          </div>
          <div className="min-w-0">
            <Label>Module</Label>
            <Select value={sourceService} onChange={(e) => { setSourceService(e.target.value); setEntityType(""); setPage(0); }}>
              <option value="">All modules</option>
              {AUDIT_MODULES.map((module) => (
                <option key={module.value} value={module.value}>{module.label}</option>
              ))}
            </Select>
          </div>
          <div className="min-w-0">
            <Label>Record type</Label>
            <Select value={entityType} onChange={(e) => { setEntityType(e.target.value); setPage(0); }}>
              <option value="">All record types</option>
              {visibleRecordTypes.map((type) => (
                <option key={type.value} value={type.value}>{type.label}</option>
              ))}
            </Select>
          </div>
          <div className="min-w-0">
            <Label>Activity</Label>
            <Select value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }}>
              <option value="">All activities</option>
              {AUDIT_ACTIVITIES.map((activity) => (
                <option key={activity.value} value={activity.value}>{activity.label}</option>
              ))}
            </Select>
          </div>
          <DateRangeFields
            type="date"
            from={from}
            to={to}
            onFromChange={(value) => { setFrom(value); setPage(0); }}
            onToChange={(value) => { setTo(value); setPage(0); }}
          />
          <ClearFiltersButton size="sm" className="justify-self-start" disabled={!hasFilters} onClick={clear} />
        </div>

        {invalidDateRange ? null : listQ.isLoading ? (
          <div className="text-sm text-muted-foreground">Loading...</div>
        ) : listQ.isError ? (
          <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed to load audit records")}</div>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>OCCURRED AT</TableHead>
                  <TableHead>ACTOR</TableHead>
                  <TableHead>ACTIVITY</TableHead>
                  <TableHead>AFFECTED RECORD</TableHead>
                  <TableHead>STATUS</TableHead>
                  <TableHead>MODULE</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={6} className="text-center text-muted-foreground">No audit records</TableCell>
                  </TableRow>
                ) : (
                  rows.map((r) => (
                    <AuditRow key={r.id} r={r} onSelect={() => setSelected(r)} />
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        )}

        {listQ.data && !invalidDateRange && (
          <PaginationControls
            page={page}
            totalPages={totalPages}
            pageSize={PAGE_SIZE}
            totalItems={listQ.data.totalElements}
            onPageChange={setPage}
          />
        )}
      </CardContent>

      <AuditDetailsDialog record={selected} onClose={() => setSelected(null)} />
    </Card>
  );
}

function AuditRow({ r, onSelect }: { r: AuditRecordResponse; onSelect: () => void }) {
  return (
      <TableRow
        className="cursor-pointer focus-visible:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
        tabIndex={0}
        onClick={onSelect}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            onSelect();
          }
        }}
        aria-label={`View details for ${activityLabel(r.action)} ${r.entityNo ?? humanize(r.entityType)}`}
      >
        <TableCell className="whitespace-nowrap tabular-nums">{formatDateTime(r.occurredAt)}</TableCell>
        <TableCell>
          {/* Snapshot names taken at write time, so they never drift with the user record. */}
          <div className="font-medium">{r.actorName ?? "System"}</div>
          {r.actorDepartment && <div className="text-xs text-muted-foreground">{departmentLabel(r.actorDepartment)}</div>}
        </TableCell>
        <TableCell className="whitespace-nowrap text-sm">{activityLabel(r.action)}</TableCell>
        <TableCell>
          <div className="text-xs text-muted-foreground">{humanize(r.entityType)}</div>
          {r.entityNo && <div className="font-medium">{r.entityNo}</div>}
        </TableCell>
        <TableCell>
          {r.beforeStatus && r.afterStatus ? (
            <div className="flex items-center gap-1.5">
              <StatusBadge status={r.beforeStatus} />
              <ArrowRight size={13} className="shrink-0 text-muted-foreground" />
              <StatusBadge status={r.afterStatus} />
            </div>
          ) : r.afterStatus ? (
            <StatusBadge status={r.afterStatus} />
          ) : r.beforeStatus ? (
            <StatusBadge status={r.beforeStatus} />
          ) : (
            <span className="text-xs text-muted-foreground">—</span>
          )}
        </TableCell>
        <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
          {SERVICE_LABELS[r.sourceService] ?? humanize(r.sourceService)}
        </TableCell>
      </TableRow>
  );
}

function AuditDetailsDialog({ record, onClose }: { record: AuditRecordResponse | null; onClose: () => void }) {
  if (!record) return null;
  const changes = displayChanges(record.changes);
  const isCreation = record.action === "CREATE" || record.action.endsWith(".created");

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Audit details</DialogTitle>
          <p className="text-sm text-muted-foreground">{activityLabel(record.action)}</p>
        </DialogHeader>

        <dl className="grid gap-x-8 gap-y-3 rounded-md border bg-muted/20 p-4 text-sm sm:grid-cols-2">
          <Detail label="Date and time" value={formatDateTime(record.occurredAt)} />
          <Detail label="Module" value={SERVICE_LABELS[record.sourceService] ?? humanize(record.sourceService)} />
          <Detail
            label="Performed by"
            value={`${record.actorName ?? "System"}${record.actorDepartment ? ` · ${departmentLabel(record.actorDepartment)}` : ""}`}
          />
          <Detail label="Affected record" value={`${humanize(record.entityType)}${record.entityNo ? ` · ${record.entityNo}` : ""}`} />
        </dl>

        {(record.beforeStatus || record.afterStatus) && (
          <section className="mt-4">
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Status</h3>
            <StatusTransition before={record.beforeStatus} after={record.afterStatus} />
          </section>
        )}

        {record.note && (
          <section className="mt-4">
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Note</h3>
            <p className="text-sm">{record.note}</p>
          </section>
        )}

        {changes.length > 0 && (
          <section className="mt-4">
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              {isCreation ? "Created with" : "What changed"}
            </h3>
            <div className="divide-y rounded-md border">
              {changes.map(([field, value]) => (
                <div key={field} className="grid gap-1 p-3 text-sm sm:grid-cols-[9rem_1fr] sm:gap-4">
                  <div className="font-medium">{humanize(field)}</div>
                  <div className="min-w-0 break-words"><ChangeValue value={value} field={field} isCreation={isCreation} /></div>
                </div>
              ))}
            </div>
          </section>
        )}

        <DialogFooter>
          <Button type="button" onClick={onClose}>Close</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium">{value}</dd>
    </div>
  );
}

function StatusTransition({ before, after }: { before: string | null; after: string | null }) {
  return before && after ? (
    <div className="flex items-center gap-1.5">
      <StatusBadge status={before} />
      <ArrowRight size={13} className="shrink-0 text-muted-foreground" />
      <StatusBadge status={after} />
    </div>
  ) : after ? <StatusBadge status={after} /> : before ? <StatusBadge status={before} /> : null;
}

function ChangeValue({ value, field, isCreation }: { value: unknown; field: string; isCreation: boolean }) {
  const pair = changePair(value);
  if (!pair) {
    if (isCreation) return formatValue(value, field);
    return (
      <div className="grid gap-1 sm:grid-cols-[6rem_1fr]">
        <span className="text-xs text-muted-foreground">Previous</span>
        <span className="text-muted-foreground">Not recorded</span>
        <span className="text-xs text-muted-foreground">New</span>
        <span>{formatValue(value, field)}</span>
      </div>
    );
  }

  if (Array.isArray(pair.from) && Array.isArray(pair.to)) {
    const before = new Set(pair.from.map(String));
    const after = new Set(pair.to.map(String));
    const added = pair.to.filter((item) => !before.has(String(item)));
    const removed = pair.from.filter((item) => !after.has(String(item)));
    return (
      <div className="grid gap-1 sm:grid-cols-[6rem_1fr]">
        <span className="text-xs text-muted-foreground">Added</span>
        <span>{formatValue(added, field)}</span>
        <span className="text-xs text-muted-foreground">Removed</span>
        <span>{formatValue(removed, field)}</span>
      </div>
    );
  }

  return (
    <div className="grid gap-1 sm:grid-cols-[6rem_1fr]">
      <span className="text-xs text-muted-foreground">Previous</span>
      <span>{formatValue(pair.from, field)}</span>
      <span className="text-xs text-muted-foreground">New</span>
      <span>{formatValue(pair.to, field)}</span>
    </div>
  );
}

function changePair(value: unknown): { from: unknown; to: unknown } | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const record = value as Record<string, unknown>;
  if ("from" in record && "to" in record) return { from: record.from, to: record.to };
  if ("before" in record && "after" in record) return { from: record.before, to: record.after };
  return null;
}

// Treat selected dates as whole days in the user's timezone. In particular,
// "To" must include the entire selected day instead of stopping at midnight.
function toDayBoundary(localDate: string, endOfDay: boolean): string | undefined {
  if (!localDate) return undefined;
  const d = new Date(`${localDate}T${endOfDay ? "23:59:59.999" : "00:00:00.000"}`);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

// Audit payloads carry whatever each service put in them; render them as
// readable values rather than raw JSON.
function formatValue(value: unknown, field?: string): string {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (Array.isArray(value)) {
    if (!value.length) return "—";
    if (field === "roles") return value.map((item) => roleLabel(String(item))).join(", ");
    if (field === "permissions") return value.map((item) => permissionLabel(String(item))).join(", ");
    return value.map((item) => formatValue(item)).join(", ");
  }
  if (typeof value === "object") {
    return Object.entries(value as Record<string, unknown>)
      .map(([k, v]) => `${humanize(k)}: ${formatValue(v)}`)
      .join(" · ");
  }
  const text = String(value);
  if (field === "department") return departmentLabel(text);
  return /^[A-Z][A-Z0-9_]*$/.test(text) ? humanize(text) : text;
}

function displayChanges(changes: Record<string, unknown> | null): Array<[string, unknown]> {
  if (!changes) return [];
  return Object.entries(changes).filter(([field]) => field !== "trigger" && field !== "customerId");
}

function activityLabel(action: string): string {
  const labels: Record<string, string> = {
    "role.permissions_replaced": "Role permissions updated",
    "user.roles_updated": "User roles updated",
    "user.created": "User created",
    "user.updated": "User updated",
    "user.enabled": "User enabled",
    "user.disabled": "User disabled",
    STATUS_CHANGE: "Status changed",
    CREATE: "Created",
    UPDATE: "Updated",
    ATTACH: "Attachment added",
    DETACH: "Attachment removed",
  };
  return labels[action] ?? humanize(action);
}
