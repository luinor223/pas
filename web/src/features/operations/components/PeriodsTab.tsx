import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Lock, Plus } from "lucide-react";
import { Button } from "@/shared/components/button";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { StatusBadge } from "@/shared/components/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { PaginationControls } from "@/shared/components/pagination-controls";
import { formatDate, formatDateTime, localMonthInputValue } from "@/shared/lib/format";
import { formatPeriod } from "../operationsFormat";
import { operationsApi } from "../services/operationsApi";
import type { PeriodResponse } from "../types/operationsTypes";

export function PeriodsTab({
  periods, loading, error, canCreate, canLock,
}: {
  periods: PeriodResponse[];
  loading: boolean;
  error: unknown;
  canCreate: boolean;
  canLock: boolean;
}) {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [openCreate, setOpenCreate] = useState(false);
  const [lockTarget, setLockTarget] = useState<PeriodResponse | null>(null);
  const totalPages = Math.max(1, Math.ceil(periods.length / DEFAULT_PAGE_SIZE));
  const currentPage = Math.min(page, totalPages - 1);
  const visible = periods.slice(currentPage * DEFAULT_PAGE_SIZE, (currentPage + 1) * DEFAULT_PAGE_SIZE);

  const lockMutation = useMutation({
    mutationFn: (periodCode: string) => operationsApi.lockPeriod(periodCode),
    onSuccess: () => {
      setLockTarget(null);
      queryClient.invalidateQueries({ queryKey: ["operation-periods"] });
      queryClient.invalidateQueries({ queryKey: ["volume-records"] });
    },
  });

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="font-semibold">Monthly periods</h3>
          <p className="text-xs text-muted-foreground">Locking confirms the month’s volumes for billing and cannot be undone.</p>
        </div>
        {canCreate && <Button onClick={() => setOpenCreate(true)}><Plus size={16} className="mr-1.5" /> New period</Button>}
      </div>

      {loading ? (
        <p className="py-8 text-center text-sm text-muted-foreground">Loading periods...</p>
      ) : error ? (
        <p role="alert" className="text-sm text-destructive">{getApiErrorMessage(error, "Could not load periods")}</p>
      ) : periods.length === 0 ? (
        <div className="rounded-md border border-dashed p-10 text-center">
          <p className="text-sm font-medium">No periods yet</p>
          <p className="mt-1 text-xs text-muted-foreground">Create a monthly period before recording service volumes.</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>PERIOD</TableHead>
                <TableHead>DATE RANGE</TableHead>
                <TableHead>VOLUME RECORDS</TableHead>
                <TableHead>STATUS</TableHead>
                <TableHead>LOCKED BY</TableHead>
                <TableHead className="text-right">ACTION</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visible.map((period) => (
                <TableRow key={period.id}>
                  <TableCell>
                    <div className="font-semibold">{formatPeriod(period.periodCode)}</div>
                    <div className="text-xs text-muted-foreground">{period.periodCode}</div>
                  </TableCell>
                  <TableCell>{formatDate(period.startDate)}–{formatDate(period.endDate)}</TableCell>
                  <TableCell>{period.volumeCount}</TableCell>
                  <TableCell><StatusBadge status={period.status} /></TableCell>
                  <TableCell>
                    {period.status === "LOCKED" ? (
                      <div>
                        <div>{period.lockedByName || "Unknown user"}</div>
                        <div className="text-xs text-muted-foreground">{formatDateTime(period.lockedAt)}</div>
                      </div>
                    ) : "—"}
                  </TableCell>
                  <TableCell className="text-right">
                    {canLock && period.status === "OPEN" && (
                      <Button size="sm" variant="outline" onClick={() => { lockMutation.reset(); setLockTarget(period); }}>
                        <Lock size={14} className="mr-1.5" /> Lock period
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      {!loading && !error && periods.length > 0 && (
        <PaginationControls page={currentPage} totalPages={totalPages} pageSize={DEFAULT_PAGE_SIZE} totalItems={periods.length} onPageChange={setPage} />
      )}

      {openCreate && <CreatePeriodDialog existing={periods} onClose={() => setOpenCreate(false)} />}

      <ConfirmDialog
        open={Boolean(lockTarget)}
        title={`Lock ${lockTarget ? formatPeriod(lockTarget.periodCode) : "this period"}?`}
        body={
          <div className="space-y-2">
            <p>This confirms {lockTarget?.volumeCount ?? 0} volume records for billing.</p>
            <p className="text-muted-foreground">The period cannot be reopened. Only users with special access can make later changes, and those changes are recorded in the activity history.</p>
          </div>
        }
        confirmLabel="Lock period"
        pendingLabel="Locking..."
        pending={lockMutation.isPending}
        error={lockMutation.error}
        cancelLabel="Cancel"
        onCancel={() => setLockTarget(null)}
        onConfirm={() => { if (lockTarget) lockMutation.mutate(lockTarget.periodCode); }}
      />
    </div>
  );
}

function CreatePeriodDialog({ existing, onClose }: { existing: PeriodResponse[]; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [periodCode, setPeriodCode] = useState(localMonthInputValue);
  const alreadyExists = existing.some((period) => period.periodCode === periodCode);
  const createMutation = useMutation({
    mutationFn: () => operationsApi.createPeriod(periodCode),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["operation-periods"] });
      onClose();
    },
  });

  return (
    <Dialog open onOpenChange={(open) => { if (!open && !createMutation.isPending) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create monthly period</DialogTitle>
          <p className="text-sm text-muted-foreground">Volume records for this month can be added and adjusted until the period is locked.</p>
        </DialogHeader>
        <div>
          <Label>Month *</Label>
          <Input type="month" value={periodCode} onChange={(event) => setPeriodCode(event.target.value)} />
          {alreadyExists && <p role="alert" className="mt-2 text-sm text-destructive">This period already exists.</p>}
          {createMutation.isError && <p role="alert" className="mt-2 text-sm text-destructive">{getApiErrorMessage(createMutation.error, "Could not create this period")}</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={createMutation.isPending} onClick={onClose}>Cancel</Button>
          <Button disabled={!periodCode || alreadyExists || createMutation.isPending} onClick={() => createMutation.mutate()}>
            {createMutation.isPending ? "Creating..." : "Create period"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
