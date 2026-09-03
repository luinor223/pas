import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, ArrowRight } from "lucide-react";
import { Button } from "@/shared/components/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import { StatusBadge } from "@/shared/components/status-badge";
import { Forbidden } from "@/shared/components/Forbidden";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { auditRecordsQuery } from "../hooks/auditQueries";
import type { AuditRecordResponse } from "../types/auditTypes";

const PAGE_SIZE = 25;

// Closed set: the V1 CHECK constraint and AuditIngestService agree on these seven.
const SOURCE_SERVICES = [
  "identity-service",
  "contract-service",
  "pricing-service",
  "operations-service",
  "billing-service",
  "workflow-service",
  "esign-service",
];

export function AuditRecordTable() {
  const canView = useHasPermission("audit:view_all");
  const [sourceService, setSourceService] = useState("");
  const [entityType, setEntityType] = useState("");
  const [entityNo, setEntityNo] = useState("");
  const [action, setAction] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<string | null>(null);

  // enabled: the early Forbidden return below does not stop the hook, so an
  // unauthorized user would otherwise fire a 403 on every render.
  const listQ = useQuery({
    ...auditRecordsQuery({
      sourceService: sourceService || undefined,
      entityType: entityType || undefined,
      entityNo: entityNo || undefined,
      action: action || undefined,
      from: toInstant(from),
      to: toInstant(to),
      page,
      size: PAGE_SIZE,
      sort: "occurredAt,desc",
    }),
    enabled: canView,
  });

  const rows = listQ.data?.content ?? [];
  const totalPages = listQ.data?.totalPages ?? 1;

  function clear() {
    setSourceService("");
    setEntityType("");
    setEntityNo("");
    setAction("");
    setFrom("");
    setTo("");
    setPage(0);
  }

  // Server is fail-closed on audit:view_all; gate the page rather than render a 403.
  if (!canView) return <Forbidden message="Viewing the audit trail requires audit:view_all." />;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Audit Log ({listQ.data?.totalElements ?? 0})</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="grid grid-cols-1 items-end gap-2 lg:grid-cols-6">
          <div>
            <Label>Source service</Label>
            <Select value={sourceService} onChange={(e) => { setSourceService(e.target.value); setPage(0); }}>
              <option value="">All services</option>
              {SOURCE_SERVICES.map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </Select>
          </div>
          <div>
            <Label>Entity type</Label>
            <Input placeholder="e.g. CONTRACT" value={entityType} onChange={(e) => { setEntityType(e.target.value); setPage(0); }} />
          </div>
          <div>
            <Label>Entity no</Label>
            <Input placeholder="e.g. HD-2026-001" value={entityNo} onChange={(e) => { setEntityNo(e.target.value); setPage(0); }} />
          </div>
          <div>
            <Label>Action</Label>
            <Input placeholder="e.g. SUBMIT" value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }} />
          </div>
          <div>
            <Label>From</Label>
            <Input type="datetime-local" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} />
          </div>
          <div className="flex gap-2">
            <div className="flex-1">
              <Label>To</Label>
              <Input type="datetime-local" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} />
            </div>
            <Button variant="outline" size="sm" className="self-end" onClick={clear}>Clear</Button>
          </div>
        </div>

        {listQ.isLoading ? (
          <div className="text-sm text-muted-foreground">Loading...</div>
        ) : listQ.isError ? (
          <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed to load audit records")}</div>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-8" />
                  <TableHead>OCCURRED AT</TableHead>
                  <TableHead>ACTOR</TableHead>
                  <TableHead>ACTION</TableHead>
                  <TableHead>ENTITY</TableHead>
                  <TableHead>STATUS</TableHead>
                  <TableHead>SOURCE</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7} className="text-center text-muted-foreground">No audit records</TableCell>
                  </TableRow>
                ) : (
                  rows.map((r) => (
                    <AuditRow
                      key={r.id}
                      r={r}
                      open={expanded === r.id}
                      onToggle={() => setExpanded((cur) => (cur === r.id ? null : r.id))}
                    />
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        )}

        <div className="flex items-center justify-between text-sm">
          <span className="text-xs text-muted-foreground">Rows per page: {PAGE_SIZE}</span>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              Previous
            </Button>
            <span className="py-1 text-xs text-muted-foreground">Page {page + 1} · {totalPages}</span>
            <Button size="sm" variant="outline" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>
              Next
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

function AuditRow({ r, open, onToggle }: { r: AuditRecordResponse; open: boolean; onToggle: () => void }) {
  const hasDetail = (r.changes && Object.keys(r.changes).length > 0) || !!r.note || !!r.ipAddress;
  return (
    <>
      <TableRow>
        <TableCell className="align-top">
          {hasDetail && (
            <button
              type="button"
              onClick={onToggle}
              className="rounded p-0.5 text-muted-foreground hover:text-foreground"
              aria-label={open ? "Hide changes" : "Show changes"}
              aria-expanded={open}
            >
              {open ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
            </button>
          )}
        </TableCell>
        <TableCell className="whitespace-nowrap tabular-nums">{formatTime(r.occurredAt)}</TableCell>
        <TableCell>
          {/* Snapshot names taken at write time, so they never drift with the user record. */}
          <div className="font-medium">{r.actorName ?? "System"}</div>
          {r.actorDepartment && <div className="text-xs text-muted-foreground">{r.actorDepartment}</div>}
        </TableCell>
        <TableCell className="whitespace-nowrap font-mono text-xs">{r.action}</TableCell>
        <TableCell>
          <div className="font-mono text-xs text-muted-foreground">{r.entityType}</div>
          {r.entityNo && <div className="font-medium">{r.entityNo}</div>}
        </TableCell>
        <TableCell>
          {r.beforeStatus || r.afterStatus ? (
            <div className="flex items-center gap-1.5">
              {r.beforeStatus ? <StatusBadge status={r.beforeStatus} /> : <span className="text-xs text-muted-foreground">—</span>}
              <ArrowRight size={13} className="shrink-0 text-muted-foreground" />
              {r.afterStatus ? <StatusBadge status={r.afterStatus} /> : <span className="text-xs text-muted-foreground">—</span>}
            </div>
          ) : (
            <span className="text-xs text-muted-foreground">—</span>
          )}
        </TableCell>
        <TableCell className="whitespace-nowrap text-xs text-muted-foreground">{r.sourceService}</TableCell>
      </TableRow>
      {open && (
        <TableRow>
          <TableCell colSpan={7} className="bg-muted/40">
            <div className="space-y-2 py-1">
              {r.note && (
                <div className="text-sm">
                  <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Note</span>
                  <p className="mt-0.5">{r.note}</p>
                </div>
              )}
              {r.changes && Object.keys(r.changes).length > 0 && (
                <div>
                  <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Changes</span>
                  {/* Arbitrary per-context JSON, so it is shown as-is rather than given columns. */}
                  <pre className="mt-0.5 overflow-x-auto rounded bg-background p-2 font-mono text-xs">
                    {JSON.stringify(r.changes, null, 2)}
                  </pre>
                </div>
              )}
              {r.ipAddress && (
                <div className="text-xs text-muted-foreground">
                  IP <span className="font-mono">{r.ipAddress}</span>
                </div>
              )}
            </div>
          </TableCell>
        </TableRow>
      )}
    </>
  );
}

// datetime-local has no offset; Instant needs one, so send UTC.
function toInstant(local: string): string | undefined {
  if (!local) return undefined;
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toISOString().slice(0, 19).replace("T", " ");
}
