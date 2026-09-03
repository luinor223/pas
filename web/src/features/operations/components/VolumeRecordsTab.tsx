import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Pencil, Plus } from "lucide-react";
import { Button } from "@/shared/components/button";
import { ClearFiltersButton } from "@/shared/components/clear-filters-button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { FilterBar } from "@/shared/components/filter-bar";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { PaginationControls } from "@/shared/components/pagination-controls";
import { SearchInput } from "@/shared/components/search-input";
import { Select } from "@/shared/components/select";
import { StatusBadge } from "@/shared/components/status-badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/shared/components/table";
import { Textarea } from "@/shared/components/textarea";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { contractQuery, contractsQuery } from "@/features/contract/hooks/contractQueries";
import { ContractPicker } from "@/features/contract/components/ContractPicker";
import type { ContractResponse } from "@/features/contract/types/contractTypes";
import { serviceItemsQuery } from "@/features/pricing/hooks/pricingQueries";
import { formatDateTime } from "@/shared/lib/format";
import { volumesQuery } from "../hooks/operationsQueries";
import { formatPeriod, formatQuantity } from "../operationsFormat";
import { operationsApi } from "../services/operationsApi";
import type { PeriodResponse, VolumeResponse } from "../types/operationsTypes";

export function VolumeRecordsTab({
  periods, periodsLoading, periodsError, canWrite, canEditLocked,
}: {
  periods: PeriodResponse[];
  periodsLoading: boolean;
  periodsError: unknown;
  canWrite: boolean;
  canEditLocked: boolean;
}) {
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [periodCode, setPeriodCode] = useState("");
  const [contractId, setContractId] = useState("");
  const [serviceCode, setServiceCode] = useState("");
  const [page, setPage] = useState(0);
  const [openCreate, setOpenCreate] = useState(false);
  const [editTarget, setEditTarget] = useState<VolumeResponse | null>(null);
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search.trim()), 300);
    return () => clearTimeout(timer);
  }, [search]);
  const volumes = useQuery(volumesQuery({
    periodCode: periodCode || undefined,
    contractId: contractId || undefined,
    serviceCode: serviceCode || undefined,
    q: debouncedSearch || undefined,
    page,
    size: DEFAULT_PAGE_SIZE,
  }));
  const contracts = useQuery(contractsQuery({ size: 100 }));
  const serviceItems = useQuery(serviceItemsQuery);
  const contractById = new Map((contracts.data?.content ?? []).map((contract) => [contract.id, contract]));
  const periodByCode = new Map(periods.map((period) => [period.periodCode, period]));
  const sortedPeriods = [...periods].sort((left, right) => right.periodCode.localeCompare(left.periodCode));
  const visible = volumes.data?.items ?? [];
  const totalPages = Math.max(1, volumes.data?.totalPages ?? 1);
  const totalItems = volumes.data?.totalItems ?? 0;
  const hasFilters = Boolean(search || periodCode || contractId || serviceCode);
  const canCreate = canWrite;
  const referenceError = periodsError ?? serviceItems.error;
  const referencesLoading = periodsLoading || serviceItems.isLoading;

  function clearFilters() {
    setSearch("");
    setPeriodCode("");
    setContractId("");
    setServiceCode("");
    setPage(0);
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="font-semibold">Recorded volumes</h3>
          <p className="text-xs text-muted-foreground">Quantities are captured against a contract, service, and monthly period.</p>
        </div>
        {canCreate && <Button disabled={referencesLoading || Boolean(referenceError)} onClick={() => setOpenCreate(true)}><Plus size={16} className="mr-1.5" /> New volume record</Button>}
      </div>

      {referenceError && (
        <p role="alert" className="text-sm text-destructive">{getApiErrorMessage(referenceError, "Could not load the periods or services needed to create a volume record")}</p>
      )}

      <FilterBar>
        <SearchInput
          className="w-full sm:w-64"
          label="Search volume records"
          placeholder="Search record, customer, or service"
          value={search}
          onChange={(value) => { setSearch(value); setPage(0); }}
        />
        <Select className="w-full sm:w-48" aria-label="Filter by period" value={periodCode} onChange={(event) => { setPeriodCode(event.target.value); setPage(0); }}>
          <option value="">Period: All</option>
          {sortedPeriods.map((period) => <option key={period.id} value={period.periodCode}>{formatPeriod(period.periodCode)}</option>)}
        </Select>
        <ContractPicker
          className="w-full sm:w-64"
          label=""
          placeholder="All contracts"
          value={contractId}
          onChange={(value) => { setContractId(value); setPage(0); }}
        />
        <Select className="w-full sm:w-52" aria-label="Filter by service" value={serviceCode} onChange={(event) => { setServiceCode(event.target.value); setPage(0); }}>
          <option value="">Service: All</option>
          {(serviceItems.data ?? []).map((service) => <option key={service.code} value={service.code}>{service.name}</option>)}
        </Select>
        <ClearFiltersButton className="ml-auto" disabled={!hasFilters} onClick={clearFilters} />
      </FilterBar>

      {volumes.isLoading ? (
        <p className="py-8 text-center text-sm text-muted-foreground">Loading volume records...</p>
      ) : volumes.isError ? (
        <p role="alert" className="text-sm text-destructive">{getApiErrorMessage(volumes.error, "Could not load volume records")}</p>
      ) : visible.length === 0 ? (
        <div className="rounded-md border border-dashed p-10 text-center">
          <p className="text-sm font-medium">{hasFilters ? "No volume records match your filters" : "No volume records yet"}</p>
          <p className="mt-1 text-xs text-muted-foreground">{hasFilters ? "Try changing or clearing the filters." : "Create a period, then add the completed service quantities."}</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <Table className="min-w-[1050px]">
            <TableHeader>
              <TableRow>
                <TableHead>RECORD</TableHead>
                <TableHead>CUSTOMER</TableHead>
                <TableHead>CONTRACT</TableHead>
                <TableHead>SERVICE</TableHead>
                <TableHead>PERIOD</TableHead>
                <TableHead>QUANTITY</TableHead>
                <TableHead>UPDATED</TableHead>
                <TableHead className="text-right">ACTION</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visible.map((volume) => {
                const period = periodByCode.get(volume.periodCode);
                const editable = period ? canWrite && (period.status !== "LOCKED" || canEditLocked) : false;
                return (
                  <TableRow key={volume.id}>
                    <TableCell>
                      <div className="font-semibold">{volume.recordNo}</div>
                      {volume.note && <div className="max-w-48 truncate text-xs text-muted-foreground" title={volume.note}>{volume.note}</div>}
                    </TableCell>
                    <TableCell className="font-medium">{volume.customerName}</TableCell>
                    <TableCell><ContractReference id={volume.contractId} contract={contractById.get(volume.contractId)} /></TableCell>
                    <TableCell>{volume.serviceName}</TableCell>
                    <TableCell>
                      <div>{formatPeriod(volume.periodCode)}</div>
                      {period && <StatusBadge status={period.status} className="mt-1" />}
                    </TableCell>
                    <TableCell className="font-semibold tabular-nums">{formatQuantity(volume.quantity)} {volume.unit}</TableCell>
                    <TableCell className="whitespace-nowrap text-xs" title={formatDateTime(volume.updatedAt)}>{formatDateTime(volume.updatedAt)}</TableCell>
                    <TableCell className="text-right">
                      {editable && (
                        <Button size="sm" variant="outline" onClick={() => setEditTarget(volume)}>
                          <Pencil size={14} className="mr-1.5" /> Edit
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}

      {!volumes.isLoading && !volumes.isError && visible.length > 0 && (
        <PaginationControls page={page} totalPages={totalPages} pageSize={DEFAULT_PAGE_SIZE} totalItems={totalItems} onPageChange={setPage} />
      )}

      {openCreate && (
        <CreateVolumeDialog
          periods={periods}
          services={serviceItems.data ?? []}
          canWrite={canWrite}
          canEditLocked={canEditLocked}
          preferredPeriod={periodCode}
          onClose={() => setOpenCreate(false)}
        />
      )}

      {editTarget && (
        <EditVolumeDialog
          volume={editTarget}
          period={periodByCode.get(editTarget.periodCode)}
          onClose={() => setEditTarget(null)}
        />
      )}
    </div>
  );
}

function ContractReference({ id, contract }: { id: string; contract?: ContractResponse }) {
  const fallback = useQuery({ ...contractQuery(id), enabled: !contract });
  const resolved = contract ?? fallback.data;
  if (!resolved) return <span className="text-muted-foreground">{fallback.isError ? "Unavailable" : "Loading..."}</span>;
  return <Link to="/contracts" search={{ id } as never} className="font-medium text-primary hover:underline">{resolved.contractNo}</Link>;
}

function CreateVolumeDialog({
  periods, services, canWrite, canEditLocked, preferredPeriod, onClose,
}: {
  periods: PeriodResponse[];
  services: { code: string; name: string; unit: string }[];
  canWrite: boolean;
  canEditLocked: boolean;
  preferredPeriod: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const eligiblePeriods = periods
    .filter((period) => canWrite && (period.status !== "LOCKED" || canEditLocked))
    .sort((left, right) => right.periodCode.localeCompare(left.periodCode));
  const initialPeriod = eligiblePeriods.some((period) => period.periodCode === preferredPeriod) ? preferredPeriod : eligiblePeriods[0]?.periodCode ?? "";
  const [periodCode, setPeriodCode] = useState(initialPeriod);
  const [contractId, setContractId] = useState("");
  const [serviceCode, setServiceCode] = useState("");
  const [quantity, setQuantity] = useState("");
  const [note, setNote] = useState("");
  const [validationError, setValidationError] = useState("");
  const selectedPeriod = periods.find((period) => period.periodCode === periodCode);
  const selectedService = services.find((service) => service.code === serviceCode);

  const createMutation = useMutation({
    mutationFn: () => operationsApi.createVolume({
      contractId,
      periodCode,
      serviceCode,
      quantity: Number(quantity),
      note: note.trim() || null,
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["volume-records"] });
      queryClient.invalidateQueries({ queryKey: ["operation-periods"] });
      onClose();
    },
  });

  function submit() {
    const error = validateQuantity(quantity);
    if (error) {
      setValidationError(error);
      return;
    }
    setValidationError("");
    createMutation.mutate();
  }

  return (
    <Dialog open onOpenChange={(open) => { if (!open && !createMutation.isPending) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New volume record</DialogTitle>
          <p className="text-sm text-muted-foreground">Record the actual quantity completed for a contract service during one month.</p>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <Label>Period *</Label>
            <Select value={periodCode} onChange={(event) => setPeriodCode(event.target.value)}>
              <option value="">Select a period</option>
              {eligiblePeriods.map((period) => (
                <option key={period.id} value={period.periodCode}>
                  {formatPeriod(period.periodCode)}{period.status === "LOCKED" ? " · Locked (special access)" : ""}
                </option>
              ))}
            </Select>
            {eligiblePeriods.length === 0 && <p className="mt-1 text-xs text-muted-foreground">No period is available for entry. Create an open period first.</p>}
          </div>
          <ContractPicker
            value={contractId}
            onChange={setContractId}
            label="Active contract *"
            placeholder="Search active contracts..."
            statuses={["ACTIVE"]}
            allowClear={false}
          />
          <div>
            <Label>Service *</Label>
            <Select value={serviceCode} onChange={(event) => setServiceCode(event.target.value)}>
              <option value="">Select a service</option>
              {services.map((service) => <option key={service.code} value={service.code}>{service.name} · {service.unit}</option>)}
            </Select>
          </div>
          <div>
            <Label>Quantity{selectedService ? ` (${selectedService.unit})` : ""} *</Label>
            <Input type="number" min="0" step="0.001" inputMode="decimal" value={quantity} onChange={(event) => { setQuantity(event.target.value); setValidationError(""); }} placeholder="0" />
          </div>
          <div>
            <Label>Note <span className="font-normal text-muted-foreground">(optional)</span></Label>
            <Textarea rows={3} value={note} onChange={(event) => setNote(event.target.value)} placeholder="Add useful operational context..." />
          </div>
          {selectedPeriod?.status === "LOCKED" && (
            <div className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800">
              This period is locked. You are using special access, and this addition will be recorded in the activity history.
            </div>
          )}
          {(validationError || createMutation.isError) && (
            <p role="alert" className="text-sm text-destructive">{validationError || getApiErrorMessage(createMutation.error, "Could not create this volume record")}</p>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={createMutation.isPending} onClick={onClose}>Cancel</Button>
          <Button disabled={!periodCode || !contractId || !serviceCode || quantity === "" || createMutation.isPending} onClick={submit}>
            {createMutation.isPending ? "Creating..." : "Create record"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function EditVolumeDialog({ volume, period, onClose }: { volume: VolumeResponse; period?: PeriodResponse; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [quantity, setQuantity] = useState(String(volume.quantity));
  const [note, setNote] = useState(volume.note ?? "");
  const [validationError, setValidationError] = useState("");
  const updateMutation = useMutation({
    mutationFn: () => operationsApi.updateVolume(volume.id, { quantity: Number(quantity), note: note.trim() || null }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["volume-records"] });
      onClose();
    },
  });

  function submit() {
    const error = validateQuantity(quantity);
    if (error) {
      setValidationError(error);
      return;
    }
    setValidationError("");
    updateMutation.mutate();
  }

  return (
    <Dialog open onOpenChange={(open) => { if (!open && !updateMutation.isPending) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Edit {volume.recordNo}</DialogTitle>
          <p className="text-sm text-muted-foreground">{volume.customerName} · {volume.serviceName} · {formatPeriod(volume.periodCode)}</p>
        </DialogHeader>
        <div className="space-y-4">
          {period?.status === "LOCKED" && (
            <div className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800">
              This period is locked. You are using special access, and the before-and-after quantities will be recorded in the activity history.
            </div>
          )}
          <div>
            <Label>Quantity ({volume.unit}) *</Label>
            <Input autoFocus type="number" min="0" step="0.001" inputMode="decimal" value={quantity} onChange={(event) => { setQuantity(event.target.value); setValidationError(""); }} />
          </div>
          <div>
            <Label>Note <span className="font-normal text-muted-foreground">(optional)</span></Label>
            <Textarea rows={3} value={note} onChange={(event) => setNote(event.target.value)} placeholder="Explain any relevant adjustment..." />
          </div>
          {(validationError || updateMutation.isError) && (
            <p role="alert" className="text-sm text-destructive">{validationError || getApiErrorMessage(updateMutation.error, "Could not update this volume record")}</p>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" disabled={updateMutation.isPending} onClick={onClose}>Cancel</Button>
          <Button disabled={quantity === "" || updateMutation.isPending} onClick={submit}>
            {updateMutation.isPending ? "Saving..." : "Save changes"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function validateQuantity(value: string): string {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) return "Quantity must be a valid number and cannot be negative.";
  const decimals = value.split(".")[1]?.length ?? 0;
  if (decimals > 3) return "Quantity can have at most three decimal places.";
  return "";
}
