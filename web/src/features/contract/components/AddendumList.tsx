import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useCallback, useId, useState, useMemo } from "react";
import { addendaQuery, contractQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import type { AddendumResponse } from "../types/contractTypes";
import { Button } from "@/shared/components/button";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Textarea } from "@/shared/components/textarea";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/shared/components/dialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Badge } from "@/shared/components/badge";
import { StatusBadge } from "@/shared/components/status-badge";
import { useForm, useFieldArray } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { Link, useNavigate } from "@tanstack/react-router";
import { ClearFiltersButton } from "@/shared/components/clear-filters-button";
import { FilterBar } from "@/shared/components/filter-bar";
import { SearchInput } from "@/shared/components/search-input";
import { ADDENDUM_CHANGE_TYPES as CHANGE_TYPES, addendumChangeTypeLabel } from "../contractOptions";
import { statusLabel } from "@/shared/lib/labels";
import { formatDateTime } from "@/shared/lib/format";
import { ContractPicker } from "./ContractPicker";
import { useDebouncedUrlValue } from "@/shared/hooks/use-debounced-url-value";
import { useRecoverOutOfRangePage } from "@/shared/hooks/use-recover-out-of-range-page";
import { ADDENDUM_STATUSES, type AddendumRouteSearch } from "../contractSearchParams";

const PAGE_SIZE = DEFAULT_PAGE_SIZE;

const lineSchema = z.object({
  serviceCode: z.string().min(1),
  serviceName: z.string().min(1),
  unit: z.string().optional().nullable(),
  scopeNote: z.string().optional().nullable(),
});

const schema = z.object({
  contractId: z.string().min(1),
  changeType: z.string().min(1),
  description: z.string().optional().nullable(),
  effectiveFrom: z.string().min(1),
  newValidTo: z.string().optional().nullable(),
  paymentTermOverride: z.string().optional().nullable(),
  services: z.array(lineSchema).optional(),
  version: z.number().optional().nullable(),
}).superRefine((d, ctx) => {
  if (d.changeType === "TERM_EXTENSION" && !d.newValidTo) ctx.addIssue({ code: "custom", path: ["newValidTo"], message: "New valid-to date is required for a term extension" });
  if (d.changeType === "PAYMENT_TERMS" && !d.paymentTermOverride) ctx.addIssue({ code: "custom", path: ["paymentTermOverride"], message: "Payment term is required for a payment terms change" });
  if (d.changeType === "ADDED_SERVICE" && (!d.services || d.services.length === 0)) ctx.addIssue({ code: "custom", path: ["services"], message: "Add at least one service" });
});

type FormData = z.infer<typeof schema>;

export function AddendumList({ search }: { search: AddendumRouteSearch }) {
  const formId = useId();
  const qc = useQueryClient();
  const navigate = useNavigate({ from: "/addenda" });
  const canRead = useHasPermission("addendum:read");
  const canWrite = useHasPermission("addendum:write");
  const canReadContracts = useHasPermission("contract:read");
  const canCreate = canWrite && canReadContracts;
  // Deep-link default for the create form (e.g. contract detail's Create addendum).
  // There is intentionally no contract filter: Figma has none, and contract-scoped
  // addenda live on the contract detail page.
  const defaultContractId = search.contractId ?? "";
  const status = search.status ?? "";
  const changeType = search.changeType ?? "";
  const q = search.q ?? "";
  const page = search.page ?? 0;
  const snapshotCursor = search.cursor;
  const [openCreate, setOpenCreate] = useState(false);
  const hasFilters = !!(status || changeType || q);

  const [searchText, setSearchText] = useDebouncedUrlValue(q, (value) => navigate({
    to: "/addenda",
    search: (previous) => ({ ...previous, q: value || undefined, page: undefined, cursor: undefined }),
  }));
  const updateList = useCallback((patch: Partial<AddendumRouteSearch>, replace = false) => navigate({
    to: "/addenda",
    search: (previous) => ({ ...previous, q: searchText || undefined, ...patch }),
    replace,
  }), [navigate, searchText]);
  const restartListing = useCallback((patch: Partial<AddendumRouteSearch> = {}) =>
    updateList({ ...patch, page: undefined, cursor: undefined }), [updateList]);

  const listQ = useQuery(addendaQuery({ status: status || undefined, changeType: changeType || undefined, q: q || undefined, page, size: PAGE_SIZE, cursor: snapshotCursor }));
  const defaultContractQ = useQuery({ ...contractQuery(defaultContractId), enabled: canCreate && Boolean(defaultContractId) });
  const pageItems = listQ.data?.content ?? [];
  const items = pageItems;

  const changePage = (nextPage: number) => {
    updateList({ page: nextPage || undefined, cursor: snapshotCursor ?? listQ.data?.cursor });
  };
  const recoverFirstPage = useCallback(() => {
    qc.removeQueries({ queryKey: ["addenda"] });
    updateList({ page: undefined, cursor: undefined }, true);
  }, [qc, updateList]);
  useRecoverOutOfRangePage({ ready: listQ.isSuccess, page, totalPages: listQ.data?.totalPages ?? 0, totalItems: listQ.data?.totalElements ?? 0, recover: recoverFirstPage });

  const { register, handleSubmit, control, reset, watch, setValue, formState: { errors } } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { contractId: "", changeType: CHANGE_TYPES[0], description: "", effectiveFrom: new Date().toISOString().slice(0, 10), newValidTo: "", paymentTermOverride: "", services: [] },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "services" });
  const watchedType = watch("changeType");
  const selectedContractId = watch("contractId");

  const createMut = useMutation({
    mutationFn: (data: FormData) => contractApi.createAddendum({
      contractId: data.contractId, changeType: data.changeType, description: data.description || null, effectiveFrom: data.effectiveFrom,
      newValidTo: data.newValidTo || null, paymentTermOverride: data.paymentTermOverride || null,
      services: (data.services ?? []).map((s) => ({ serviceCode: s.serviceCode, serviceName: s.serviceName, unit: s.unit || null, scopeNote: s.scopeNote || null })),
    }),
    onSuccess: (created) => {
      qc.setQueryData(["addendum", created.id], created);
      qc.invalidateQueries({ queryKey: ["addenda"] });
      setOpenCreate(false);
      navigate({
        to: "/addenda",
        search: (previous) => ({ ...previous, q: searchText || undefined,
          id: created.id,
        }),
      });
    },
  });
  const columns = useMemo<ColumnDef<AddendumResponse>[]>(() => [
    {
      accessorKey: "addendumNo", header: "NO",
      cell: ({ row }) => (
        <div>
          <Link to="/addenda" search={{ ...search, q: searchText || undefined, id: row.original.id }} className="font-medium text-blue-600 hover:underline">{row.original.addendumNo}</Link>
          <div className="mt-0.5 text-xs text-muted-foreground">Created {formatDateTime(row.original.createdAt)}</div>
        </div>
      ),
    },
    {
      accessorKey: "contractNo", header: "CONTRACT",
      cell: ({ row }) => <Link to="/contracts" search={{ id: row.original.contractId } as never} className="font-medium text-blue-600 hover:underline">{row.original.contractNo}</Link>,
    },
    { accessorKey: "changeType", header: "TYPE", cell: ({ row }) => <Badge variant="secondary">{addendumChangeTypeLabel(row.original.changeType)}</Badge> },
    { accessorKey: "effectiveFrom", header: "EFFECTIVE FROM" },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
  ], [search, searchText]);

  if (!canRead) return <Card><CardContent className="p-6 text-sm">You do not have access to addenda.</CardContent></Card>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <CardTitle>Addenda ({listQ.data?.totalElements ?? 0})</CardTitle>
          <div className="flex items-center gap-2">
            <SearchInput
              className="w-56 lg:w-72"
              label="Search addenda"
              placeholder="Search no/description..."
              value={searchText}
              onChange={setSearchText}
            />
          {canCreate && <Button disabled={Boolean(defaultContractId) && defaultContractQ.isPending} onClick={() => { reset({ contractId: defaultContractQ.data?.canCreateAddendum ? defaultContractQ.data.id : "", changeType: changeType || CHANGE_TYPES[0], description: "", effectiveFrom: new Date().toISOString().slice(0, 10), newValidTo: "", paymentTermOverride: "", services: [] }); setOpenCreate(true); }}>+ New Addendum</Button>}
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <FilterBar>
            <Select className="w-full sm:w-48" aria-label="Filter by status" value={status} onChange={(e) => restartListing({ status: (e.target.value || undefined) as AddendumRouteSearch["status"] })}>
              <option value="">Status: All</option>{ADDENDUM_STATUSES.map((s) => <option key={s} value={s}>{statusLabel(s)}</option>)}
            </Select>
            <Select className="w-full sm:w-48" aria-label="Filter by change type" value={changeType} onChange={(e) => restartListing({ changeType: (e.target.value || undefined) as AddendumRouteSearch["changeType"] })}>
              <option value="">Change: All</option>{CHANGE_TYPES.map((t) => <option key={t} value={t}>{addendumChangeTypeLabel(t)}</option>)}
            </Select>
            <ClearFiltersButton size="sm" disabled={!hasFilters} onClick={() => restartListing({ status: undefined, changeType: undefined, q: undefined })} />
          </FilterBar>
          {listQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : listQ.isError ? <div className="space-y-2"><div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed")}</div>{snapshotCursor && <Button variant="outline" size="sm" onClick={recoverFirstPage}>Return to first page</Button>}</div> : <DataTable columns={columns} data={items} emptyMessage="No addenda" pageSize={PAGE_SIZE} serverPagination={{ page: listQ.data?.number ?? page, totalPages: listQ.data?.totalPages ?? 0, totalItems: listQ.data?.totalElements ?? 0, onPageChange: changePage }} />}
        </CardContent>
      </Card>

      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-auto">
          <DialogHeader><DialogTitle>Create addendum</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMut.mutate(d))} className="space-y-3">
            <div>
              <ContractPicker
                value={selectedContractId}
                onChange={(id) => setValue("contractId", id, { shouldValidate: true })}
                label="Contract *"
                placeholder="Search eligible contracts..."
                statuses={["APPROVED", "ACTIVE"]}
                eligibleForAddendum
                allowClear={false}
              />
              {errors.contractId && <p className="text-xs text-destructive">{errors.contractId.message}</p>}
            </div>
            <div><Label htmlFor={`${formId}-change-type`}>Change type *</Label><Select id={`${formId}-change-type`} {...register("changeType")}>{CHANGE_TYPES.map((t) => <option key={t} value={t}>{addendumChangeTypeLabel(t)}</option>)}</Select></div>
            <div><Label htmlFor={`${formId}-description`}>Description</Label><Textarea id={`${formId}-description`} {...register("description")} /></div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2"><div><Label htmlFor={`${formId}-effective-from`}>Effective from *</Label><Input id={`${formId}-effective-from`} type="date" {...register("effectiveFrom")} />{errors.effectiveFrom && <p className="text-xs text-destructive">{errors.effectiveFrom.message}</p>}</div>{watchedType==="TERM_EXTENSION" && <div><Label htmlFor={`${formId}-new-valid-to`}>New valid to *</Label><Input id={`${formId}-new-valid-to`} type="date" {...register("newValidTo")} />{errors.newValidTo && <p className="text-xs text-destructive">{String(errors.newValidTo.message)}</p>}</div>}{watchedType==="PAYMENT_TERMS" && <div><Label htmlFor={`${formId}-payment-term-override`}>Payment term override *</Label><Input id={`${formId}-payment-term-override`} {...register("paymentTermOverride")} /></div>}</div>
            {watchedType==="ADDED_SERVICE" && (
              <div>
                <div className="text-sm font-medium">Services</div>
                <div className="space-y-1 border rounded p-2">
                  {fields.map((f,i) => (
                    <div key={f.id} className="grid grid-cols-1 items-end gap-1 sm:grid-cols-12">
                      <div className="sm:col-span-3"><Label className="sr-only" htmlFor={`${formId}-service-${i}-code`}>Service {i + 1} code</Label><Input id={`${formId}-service-${i}-code`} {...register(`services.${i}.serviceCode` as const)} placeholder="Code" /></div>
                      <div className="sm:col-span-4"><Label className="sr-only" htmlFor={`${formId}-service-${i}-name`}>Service {i + 1} name</Label><Input id={`${formId}-service-${i}-name`} {...register(`services.${i}.serviceName` as const)} placeholder="Name" /></div>
                      <div className="sm:col-span-2"><Label className="sr-only" htmlFor={`${formId}-service-${i}-unit`}>Service {i + 1} unit</Label><Input id={`${formId}-service-${i}-unit`} {...register(`services.${i}.unit` as const)} placeholder="Unit" /></div>
                      <div className="sm:col-span-2"><Label className="sr-only" htmlFor={`${formId}-service-${i}-scope`}>Service {i + 1} scope</Label><Input id={`${formId}-service-${i}-scope`} {...register(`services.${i}.scopeNote` as const)} placeholder="Scope" /></div>
                      <Button type="button" variant="ghost" size="sm" className="sm:col-span-1" aria-label={`Remove service ${i + 1}`} onClick={() => remove(i)}>×</Button>
                    </div>
                  ))}
                  <Button type="button" size="sm" variant="outline" onClick={() => append({ serviceCode:"", serviceName:"", unit:"", scopeNote:""})}>+ Service</Button>
                </div>
              </div>
            )}
            {createMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(createMut.error, "Create failed")}</div>}
            <DialogFooter><Button type="button" variant="outline" onClick={() => setOpenCreate(false)}>Cancel</Button><Button type="submit" disabled={createMut.isPending}>{createMut.isPending?"Creating...":"Create"}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

    </div>
  );
}
