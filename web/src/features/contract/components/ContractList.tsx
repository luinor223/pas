import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useCallback, useId, useState, useMemo } from "react";
import { contractsQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import type { ContractResponse } from "../types/contractTypes";
import { Button } from "@/shared/components/button";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Textarea } from "@/shared/components/textarea";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/shared/components/dialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { StatusBadge } from "@/shared/components/status-badge";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { Link, useNavigate } from "@tanstack/react-router";
import { CustomerPicker } from "./CustomerPicker";
import { RowMenu } from "@/shared/components/row-menu";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { DateRangeFields, isInvalidDateRange } from "@/shared/components/date-range-fields";
import { ClearFiltersButton } from "@/shared/components/clear-filters-button";
import { FilterBar } from "@/shared/components/filter-bar";
import { SearchInput } from "@/shared/components/search-input";
import { formatDate, formatDateTime, formatMoney } from "@/shared/lib/format";
import { SERVICE_GROUPS } from "../contractOptions";
import { statusLabel } from "@/shared/lib/labels";
import { useDebouncedUrlValue } from "@/shared/hooks/use-debounced-url-value";
import { useRecoverOutOfRangePage } from "@/shared/hooks/use-recover-out-of-range-page";
import type { ContractRouteSearch } from "../contractSearchParams";
import { contractFormSchema, contractRequest, type ContractFormData } from "../contractForm";
import { ContractEditDialog } from "./ContractEditDialog";

const STATUSES = ["DRAFT", "SUBMITTED", "UNDER_REVIEW", "APPROVED", "ACTIVE", "EXPIRED", "REJECTED", "REVISION_REQUESTED", "CANCELLED"];
const PAGE_SIZE = DEFAULT_PAGE_SIZE;

export function ContractList({ search }: { search: ContractRouteSearch }) {
  const formId = useId();
  const qc = useQueryClient();
  const navigate = useNavigate({ from: "/contracts" });
  const canRead = useHasPermission("contract:read");
  const canWrite = useHasPermission("contract:write");
  const q = search.q ?? "";
  const customerId = search.customerId ?? "";
  const status = search.status ?? "";
  const serviceGroup = search.serviceGroup ?? "";
  const validFromFrom = search.validFromFrom ?? "";
  const validToTo = search.validToTo ?? "";
  const page = search.page ?? 0;
  const snapshotCursor = search.cursor;
  const [openCreate, setOpenCreate] = useState(false);
  const [editing, setEditing] = useState<ContractResponse | null>(null);
  const invalidFilterRange = isInvalidDateRange(validFromFrom, validToTo);
  const hasFilters = !!(q || customerId || status || serviceGroup || validFromFrom || validToTo);

  const [searchText, setSearchText] = useDebouncedUrlValue(q, (value) => navigate({
    to: "/contracts",
    search: (previous) => ({ ...previous, q: value || undefined, page: undefined, cursor: undefined }),
  }));
  const updateList = useCallback((patch: Partial<ContractRouteSearch>, replace = false) => navigate({
    to: "/contracts",
    search: (previous) => ({ ...previous, q: searchText || undefined, ...patch }),
    replace,
  }), [navigate, searchText]);
  const restartListing = useCallback((patch: Partial<ContractRouteSearch> = {}) =>
    updateList({ ...patch, page: undefined, cursor: undefined }), [updateList]);

  const listParams = { q: q || undefined, customerId: customerId || undefined, status: status || undefined, serviceGroup: serviceGroup || undefined, validFromFrom: validFromFrom || undefined, validToTo: validToTo || undefined, page, size: PAGE_SIZE, cursor: snapshotCursor };
  const listQ = useQuery({ ...contractsQuery(listParams), enabled: canRead && !invalidFilterRange });

  const contracts = listQ.data?.content ?? [];
  const total = listQ.data?.totalElements ?? 0;

  const changePage = (nextPage: number) => {
    updateList({ page: nextPage || undefined, cursor: snapshotCursor ?? listQ.data?.cursor });
  };
  const recoverFirstPage = useCallback(() => {
    qc.removeQueries({ queryKey: ["contracts"] });
    updateList({ page: undefined, cursor: undefined }, true);
  }, [qc, updateList]);
  useRecoverOutOfRangePage({ ready: listQ.isSuccess, page, totalPages: listQ.data?.totalPages ?? 0, totalItems: total, recover: recoverFirstPage });

  const { register, handleSubmit, reset, watch, setValue, formState: { errors } } = useForm<ContractFormData>({
    resolver: zodResolver(contractFormSchema),
    defaultValues: { customerId: "", description: "", serviceGroup: SERVICE_GROUPS[0], value: "", currency: "VND", validFrom: "", validTo: "", paymentTerm: "", billingCycle: "MONTHLY", vatRate: "", penaltyTerms: "", serviceClause: "" },
  });
  const selectedCustomerId = watch("customerId");

  const createMut = useMutation({
    mutationFn: (data: ContractFormData) => contractApi.createContract(contractRequest(data)),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["contracts"] }); setOpenCreate(false); reset(); },
  });
  const [confirmCancel, setConfirmCancel] = useState<ContractResponse | null>(null);
  const submitMut = useMutation({ mutationFn: (id: string) => contractApi.submitContract(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["contracts"] }) });
  const cancelMut = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) => contractApi.cancelContract(id, reason),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["contracts"] }); setConfirmCancel(null); },
  });
  const reviseMut = useMutation({ mutationFn: (id: string) => contractApi.reviseContract(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["contracts"] }) });

  const columns = useMemo<ColumnDef<ContractResponse>[]>(() => [
    {
      accessorKey: "contractNo", header: "CONTRACT NO.",
      cell: ({ row }) => (
        <div>
          <Link to="/contracts" search={{ ...search, q: searchText || undefined, id: row.original.id }} className="font-medium text-blue-600 hover:underline">{row.original.contractNo}</Link>
          <div className="mt-0.5 text-xs text-muted-foreground">Created {formatDateTime(row.original.createdAt)}</div>
        </div>
      ),
    },
    { accessorKey: "customerName", header: "CUSTOMER", cell: ({ row }) => <span className="font-medium">{row.original.customerName}</span> },
    { accessorKey: "serviceGroup", header: "SERVICE GROUP", cell: ({ row }) => <span className="text-sm capitalize">{row.original.serviceGroup.toLowerCase().replace(/_/g, " ")}</span> },
    { accessorKey: "value", header: "VALUE", cell: ({ row }) => <span className="tabular-nums">{formatMoney(row.original.value, row.original.currency)}</span> },
    { accessorKey: "validFrom", header: "EFFECTIVE", cell: ({ row }) => <span className="text-xs">{formatDate(row.original.validFrom)}</span> },
    { accessorKey: "validTo", header: "EXPIRY", cell: ({ row }) => <span className="text-xs">{formatDate(row.original.validTo)}</span> },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      id: "actions", header: "ACTION", enableSorting: false,
      cell: ({ row }) => {
        const c = row.original;
        const items: { label: string; onClick: () => void; danger?: boolean }[] = [
          { label: "View details", onClick: () => navigate({ to: "/contracts", search: { ...search, q: searchText || undefined, id: c.id } }) },
          { label: "Download", onClick: () => navigate({ to: "/contracts", search: { ...search, q: searchText || undefined, id: c.id, tab: "attachments" } }) },
        ];
        if (c.canEdit) items.push({ label: "Edit", onClick: () => setEditing(c) });
        if (c.canSubmit) items.push({ label: "Submit for approval", onClick: () => submitMut.mutate(c.id) });
        if (c.canRevise) items.push({ label: "Revise", onClick: () => reviseMut.mutate(c.id) });
        if (c.canCreateAddendum) items.push({ label: "Create addendum", onClick: () => navigate({ to: "/addenda", search: { contractId: c.id } as never }) });
        if (c.canCreateAddendum) items.push({ label: "Renew contract", onClick: () => navigate({ to: "/addenda", search: { contractId: c.id, changeType: "TERM_EXTENSION" } as never }) });
        if (c.canCancel) items.push({ label: "Cancel contract", onClick: () => setConfirmCancel(c), danger: true });
        return (
          <div className="text-right">
            <RowMenu items={items} />
          </div>
        );
      },
    },
  ], [navigate, search, searchText]);

  if (!canRead) return <Card><CardContent className="p-6 text-sm">You do not have access to contracts.</CardContent></Card>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <CardTitle>Contracts ({total})</CardTitle>
          <div className="flex items-center gap-2">
            <SearchInput
              className="w-56 lg:w-72"
              label="Search contracts"
              placeholder="Search no/description/customer..."
              value={searchText}
              onChange={setSearchText}
            />
          {canWrite && <Button onClick={() => { reset({ customerId: "", description: "", serviceGroup: SERVICE_GROUPS[0], value: "", currency: "VND", validFrom: new Date().toISOString().slice(0, 10), validTo: new Date(Date.now() + 30*24*3600*1000).toISOString().slice(0, 10), paymentTerm: "", billingCycle: "MONTHLY", vatRate: "", penaltyTerms: "", serviceClause: "" }); setOpenCreate(true); }}>+ New Contract</Button>}
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <FilterBar className="items-center [&_input]:bg-card [&_select]:bg-card">
            <CustomerPicker
              className="w-full sm:w-52"
              label=""
              placeholder="All customers"
              value={customerId}
              onChange={(id) => restartListing({ customerId: id || undefined })}
            />
            <Select className="w-full sm:w-40" aria-label="Filter by status" value={status} onChange={(e) => restartListing({ status: (e.target.value || undefined) as ContractRouteSearch["status"] })}>
              <option value="">Status: All</option>{STATUSES.map((s) => <option key={s} value={s}>{statusLabel(s)}</option>)}
            </Select>
            <Select className="w-full sm:w-44" aria-label="Filter by service group" value={serviceGroup} onChange={(e) => restartListing({ serviceGroup: e.target.value || undefined })}>
              <option value="">Group: All</option>{SERVICE_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
            </Select>
            <DateRangeFields
              layout="inline"
              from={validFromFrom}
              to={validToTo}
              fromLabel="Effective from"
              toLabel="Expiry"
              onFromChange={(value) => restartListing({ validFromFrom: value || undefined })}
              onToChange={(value) => restartListing({ validToTo: value || undefined })}
            />
            <ClearFiltersButton className="ml-auto bg-card" disabled={!hasFilters} onClick={() => restartListing({ q: undefined, customerId: undefined, status: undefined, serviceGroup: undefined, validFromFrom: undefined, validToTo: undefined })} />
          </FilterBar>
          <div className="text-xs text-muted-foreground">Date filters show contracts whose full term falls within the selected dates.</div>
          {invalidFilterRange ? null : listQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : listQ.isError ? <div className="space-y-2"><div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed")}</div>{snapshotCursor && <Button variant="outline" size="sm" onClick={recoverFirstPage}>Return to first page</Button>}</div> : <DataTable columns={columns} data={contracts} emptyMessage="No contracts" pageSize={PAGE_SIZE} serverPagination={{ page: listQ.data?.number ?? page, totalPages: listQ.data?.totalPages ?? 0, totalItems: total, onPageChange: changePage }} />}
          {(submitMut.isError || reviseMut.isError) && <div className="text-xs text-destructive">{getApiErrorMessage((submitMut.error ?? reviseMut.error) as unknown as Error, "Action failed")}</div>}
        </CardContent>
      </Card>

      <ConfirmDialog
        open={!!confirmCancel}
        title="Cancel this contract?"
        body={
          <>
            <p>
              Contract <span className="font-medium">{confirmCancel?.contractNo}</span> for{" "}
              <span className="font-medium">{confirmCancel?.customerName}</span> will be cancelled.
            </p>
            <p className="mt-2 text-muted-foreground">
              Any approval already in progress stops, and the cancellation stays on the contract's
              history. This cannot be undone here.
            </p>
          </>
        }
        confirmLabel="Cancel contract"
        pendingLabel="Cancelling..."
        pending={cancelMut.isPending}
        error={cancelMut.isError ? cancelMut.error : undefined}
        reason={{ label: "Reason", placeholder: "Why is this contract being cancelled?" }}
        onConfirm={(reason) => confirmCancel && cancelMut.mutate({ id: confirmCancel.id, reason })}
        onCancel={() => setConfirmCancel(null)}
      />

      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-auto">
          <DialogHeader><DialogTitle>Create contract</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMut.mutate(d))} className="space-y-3">
            <div>
              <CustomerPicker value={selectedCustomerId} onChange={(id) => setValue("customerId", id, { shouldValidate: true })} label="Customer *" placeholder="Type code or name..." status="ACTIVE" />
              {errors.customerId && <p className="text-xs text-destructive">{errors.customerId.message}</p>}
            </div>
            <div><Label htmlFor={`${formId}-description`}>Description</Label><Textarea id={`${formId}-description`} {...register("description")} /></div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2"><div><Label htmlFor={`${formId}-service-group`}>Service group *</Label><Select id={`${formId}-service-group`} {...register("serviceGroup")}>{SERVICE_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}</Select></div><div><Label htmlFor={`${formId}-currency`}>Currency</Label><Input id={`${formId}-currency`} {...register("currency")} placeholder="VND" /></div></div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-3"><div><Label htmlFor={`${formId}-value`}>Value</Label><Input id={`${formId}-value`} type="number" step="0.01" {...register("value")} /></div><div><Label htmlFor={`${formId}-valid-from`}>Valid from *</Label><Input id={`${formId}-valid-from`} type="date" {...register("validFrom")} />{errors.validFrom && <p className="text-xs text-destructive">{errors.validFrom.message}</p>}</div><div><Label htmlFor={`${formId}-valid-to`}>Valid to *</Label><Input id={`${formId}-valid-to`} type="date" {...register("validTo")} />{errors.validTo && <p className="text-xs text-destructive">{errors.validTo.message}</p>}</div></div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-3"><div><Label htmlFor={`${formId}-payment-term`}>Payment term</Label><Input id={`${formId}-payment-term`} {...register("paymentTerm")} placeholder="e.g. 30D" /></div><div><Label htmlFor={`${formId}-billing-cycle`}>Billing cycle</Label><Input id={`${formId}-billing-cycle`} {...register("billingCycle")} readOnly /></div><div><Label htmlFor={`${formId}-vat-rate`}>VAT rate</Label><Input id={`${formId}-vat-rate`} type="number" step="0.01" {...register("vatRate")} /></div></div>
            <div><Label htmlFor={`${formId}-penalty-terms`}>Penalty terms</Label><Textarea id={`${formId}-penalty-terms`} {...register("penaltyTerms")} /></div>
            <div><Label htmlFor={`${formId}-service-clause`}>Service clause</Label><Textarea id={`${formId}-service-clause`} {...register("serviceClause")} /></div>
            {createMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(createMut.error, "Create failed")}</div>}
            <DialogFooter><Button type="button" variant="outline" onClick={() => setOpenCreate(false)}>Cancel</Button><Button type="submit" disabled={createMut.isPending}>{createMut.isPending ? "Creating..." : "Create"}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {editing && (
        <ContractEditDialog
          contract={editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await qc.invalidateQueries({ queryKey: ["contracts"] });
          }}
        />
      )}
    </div>
  );
}
