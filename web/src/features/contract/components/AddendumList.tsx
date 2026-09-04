import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, useMemo } from "react";
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
import { ADDENDUM_CHANGE_TYPES as CHANGE_TYPES } from "../contractOptions";
import { statusLabel } from "@/shared/lib/labels";
import { humanize } from "@/shared/lib/text";
import { ContractPicker } from "./ContractPicker";

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
  if (d.changeType === "TERM_EXTENSION" && !d.newValidTo) ctx.addIssue({ code: "custom", path: ["newValidTo"], message: "Required for TERM_EXTENSION" });
  if (d.changeType === "PAYMENT_TERMS" && !d.paymentTermOverride) ctx.addIssue({ code: "custom", path: ["paymentTermOverride"], message: "Required for PAYMENT_TERMS" });
  if (d.changeType === "ADDED_SERVICE" && (!d.services || d.services.length === 0)) ctx.addIssue({ code: "custom", path: ["services"], message: "At least one service" });
});

type FormData = z.infer<typeof schema>;

export function AddendumList() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const canRead = useHasPermission("addendum:read");
  const canWrite = useHasPermission("addendum:write");
  const canReadContracts = useHasPermission("contract:read");
  const canCreate = canWrite && canReadContracts;
  // Deep-link default for the create form (e.g. contract detail's Create addendum).
  // There is intentionally no contract filter: Figma has none, and contract-scoped
  // addenda live on the contract detail page.
  const [defaultContractId] = useState(() => new URLSearchParams(window.location.search).get("contractId") ?? "");
  const [status, setStatus] = useState("");
  const [changeType, setChangeType] = useState(() => new URLSearchParams(window.location.search).get("changeType") ?? "");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [snapshotCursor, setSnapshotCursor] = useState<string>();
  const [openCreate, setOpenCreate] = useState(false);
  const hasFilters = !!(status || changeType || q);

  const listQ = useQuery(addendaQuery({ status: status || undefined, changeType: changeType || undefined, q: q || undefined, page, size: PAGE_SIZE, cursor: snapshotCursor }));
  const defaultContractQ = useQuery({ ...contractQuery(defaultContractId), enabled: canCreate && Boolean(defaultContractId) });
  const pageItems = listQ.data?.content ?? [];
  const items = pageItems;

  const changePage = (nextPage: number) => {
    if (page === 0 && listQ.data?.cursor) setSnapshotCursor(listQ.data.cursor);
    setPage(nextPage);
  };
  const restartListing = () => { setSnapshotCursor(undefined); setPage(0); };
  const recoverFirstPage = () => { qc.removeQueries({ queryKey: ["addenda"] }); restartListing(); };

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
        search: (previous) => ({
          id: created.id,
          contractId: previous.contractId,
          changeType: previous.changeType,
        }),
      });
    },
  });
  const columns = useMemo<ColumnDef<AddendumResponse>[]>(() => [
    {
      accessorKey: "addendumNo", header: "NO",
      cell: ({ row }) => <Link to="/addenda" search={(previous) => ({ id: row.original.id, contractId: previous.contractId, changeType: previous.changeType })} className="font-medium text-blue-600 hover:underline">{row.original.addendumNo}</Link>,
    },
    {
      accessorKey: "contractNo", header: "CONTRACT",
      cell: ({ row }) => <Link to="/contracts" search={{ id: row.original.contractId } as never} className="font-medium text-blue-600 hover:underline">{row.original.contractNo}</Link>,
    },
    { accessorKey: "changeType", header: "TYPE", cell: ({ row }) => <Badge variant="secondary">{row.original.changeType}</Badge> },
    { accessorKey: "effectiveFrom", header: "EFFECTIVE FROM" },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
  ], []);

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
              value={q}
              onChange={(value) => { setQ(value); restartListing(); }}
            />
          {canCreate && <Button disabled={Boolean(defaultContractId) && defaultContractQ.isPending} onClick={() => { reset({ contractId: defaultContractQ.data?.canCreateAddendum ? defaultContractQ.data.id : "", changeType: changeType || CHANGE_TYPES[0], description: "", effectiveFrom: new Date().toISOString().slice(0, 10), newValidTo: "", paymentTermOverride: "", services: [] }); setOpenCreate(true); }}>+ New Addendum</Button>}
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <FilterBar>
                        <Select className="w-full sm:w-48" aria-label="Filter by status" value={status} onChange={(e) => { setStatus(e.target.value); restartListing(); }}>
              <option value="">Status: All</option>{["DRAFT","SUBMITTED","UNDER_REVIEW","APPROVED","ACTIVE","REJECTED","REVISION_REQUESTED","CANCELLED"].map((s) => <option key={s} value={s}>{statusLabel(s)}</option>)}
            </Select>
            <Select className="w-full sm:w-48" aria-label="Filter by change type" value={changeType} onChange={(e) => { setChangeType(e.target.value); restartListing(); }}>
              <option value="">Change: All</option>{CHANGE_TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}
            </Select>
            <ClearFiltersButton size="sm" disabled={!hasFilters} onClick={() => { setStatus(""); setChangeType(""); setQ(""); restartListing(); }} />
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
            <div><Label>Change type *</Label><Select {...register("changeType")}>{CHANGE_TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}</Select></div>
            <div><Label>Description</Label><Textarea {...register("description")} /></div>
            <div className="grid grid-cols-2 gap-2"><div><Label>Effective from *</Label><Input type="date" {...register("effectiveFrom")} />{errors.effectiveFrom && <p className="text-xs text-destructive">{errors.effectiveFrom.message}</p>}</div>{watchedType==="TERM_EXTENSION" && <div><Label>New valid to *</Label><Input type="date" {...register("newValidTo")} />{errors.newValidTo && <p className="text-xs text-destructive">{String(errors.newValidTo.message)}</p>}</div>}{watchedType==="PAYMENT_TERMS" && <div><Label>Payment term override *</Label><Input {...register("paymentTermOverride")} /></div>}</div>
            {watchedType==="ADDED_SERVICE" && (
              <div>
                <Label>Services</Label>
                <div className="space-y-1 border rounded p-2">
                  {fields.map((f,i) => (
                    <div key={f.id} className="grid grid-cols-12 gap-1 items-end">
                      <div className="col-span-3"><Input {...register(`services.${i}.serviceCode` as const)} placeholder="Code" /></div>
                      <div className="col-span-4"><Input {...register(`services.${i}.serviceName` as const)} placeholder="Name" /></div>
                      <div className="col-span-2"><Input {...register(`services.${i}.unit` as const)} placeholder="Unit" /></div>
                      <div className="col-span-2"><Input {...register(`services.${i}.scopeNote` as const)} placeholder="Scope" /></div>
                      <Button type="button" variant="ghost" size="sm" className="col-span-1" onClick={() => remove(i)}>×</Button>
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
