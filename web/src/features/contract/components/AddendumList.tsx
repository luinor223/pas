import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, useMemo } from "react";
import { addendaQuery, contractsQuery } from "../hooks/contractQueries";
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
import { useHasPermission } from "@/features/auth/hooks/usePermissions";

const CHANGE_TYPES = ["UNIT_PRICE_CHANGE", "TERM_EXTENSION", "ADDED_SERVICE", "PAYMENT_TERMS"];

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
  const canRead = useHasPermission("addendum:read");
  const canWrite = useHasPermission("addendum:write");
  const [contractId, setContractId] = useState("");
  const [status, setStatus] = useState("");
  const [changeType, setChangeType] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [openCreate, setOpenCreate] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);

  const listQ = useQuery(addendaQuery({ contractId: contractId || undefined, status: status || undefined, changeType: changeType || undefined, q: q || undefined, page, size: 20 }));
  const contractsQ = useQuery(contractsQuery({ size: 100 }));
  const items = listQ.data?.content ?? [];

  const { register, handleSubmit, control, reset, watch, formState: { errors } } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { contractId: "", changeType: CHANGE_TYPES[0], description: "", effectiveFrom: new Date().toISOString().slice(0, 10), newValidTo: "", paymentTermOverride: "", services: [] },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "services" });
  const watchedType = watch("changeType");

  const createMut = useMutation({
    mutationFn: (data: FormData) => contractApi.createAddendum({
      contractId: data.contractId, changeType: data.changeType, description: data.description || null, effectiveFrom: data.effectiveFrom,
      newValidTo: data.newValidTo || null, paymentTermOverride: data.paymentTermOverride || null,
      services: (data.services ?? []).map((s) => ({ serviceCode: s.serviceCode, serviceName: s.serviceName, unit: s.unit || null, scopeNote: s.scopeNote || null })),
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["addenda"] }); setOpenCreate(false); },
  });
  const updateMut = useMutation({
    mutationFn: (data: FormData) => {
      if (!editId) throw new Error("No edit");
      const existing = items.find((x) => x.id === editId);
      return contractApi.updateAddendum(editId, {
        contractId: data.contractId, changeType: data.changeType, description: data.description || null, effectiveFrom: data.effectiveFrom,
        newValidTo: data.newValidTo || null, paymentTermOverride: data.paymentTermOverride || null,
        services: (data.services ?? []).map((s) => ({ serviceCode: s.serviceCode, serviceName: s.serviceName, unit: s.unit || null, scopeNote: s.scopeNote || null })),
        version: existing?.version ?? 0,
      });
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["addenda"] }); setEditId(null); },
  });
  const submitMut = useMutation({ mutationFn: (id: string) => contractApi.submitAddendum(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["addenda"] }) });
  const cancelMut = useMutation({ mutationFn: (id: string) => contractApi.cancelAddendum(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["addenda"] }) });
  const reviseMut = useMutation({ mutationFn: (id: string) => contractApi.reviseAddendum(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["addenda"] }) });

  const onEdit = (a: AddendumResponse) => {
    setEditId(a.id);
    reset({
      contractId: a.contractId, changeType: a.changeType, description: a.description ?? "", effectiveFrom: a.effectiveFrom, newValidTo: a.newValidTo ?? "", paymentTermOverride: a.paymentTermOverride ?? "",
      services: a.services.map((s) => ({ serviceCode: s.serviceCode, serviceName: s.serviceName, unit: s.unit ?? "", scopeNote: s.scopeNote ?? "" })),
    });
  };

  const columns = useMemo<ColumnDef<AddendumResponse>[]>(() => [
    { accessorKey: "addendumNo", header: "NO" },
    { accessorKey: "contractNo", header: "CONTRACT" },
    { accessorKey: "changeType", header: "TYPE", cell: ({ row }) => <Badge variant="secondary">{row.original.changeType}</Badge> },
    { accessorKey: "effectiveFrom", header: "EFFECTIVE FROM" },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      id: "actions", header: "ACTIONS", enableSorting: false,
      cell: ({ row }) => {
        const a = row.original;
        return (
          <div className="flex gap-1 flex-wrap">
            {canWrite && (a.status === "DRAFT" || a.status === "REVISION_REQUESTED") && <Button size="sm" variant="outline" onClick={() => onEdit(a)}>Edit</Button>}
            {canWrite && a.status === "DRAFT" && <Button size="sm" onClick={() => submitMut.mutate(a.id)}>Submit</Button>}
            {canWrite && a.status === "REJECTED" && <Button size="sm" onClick={() => reviseMut.mutate(a.id)}>Revise</Button>}
            {canWrite && <Button size="sm" variant="destructive" onClick={() => cancelMut.mutate(a.id)}>Cancel</Button>}
          </div>
        );
      },
    },
  ], [canWrite]);

  if (!canRead) return <Card><CardContent className="p-6 text-sm">Need <code>addendum:read</code></CardContent></Card>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Addenda ({listQ.data?.totalElements ?? 0})</CardTitle>
          {canWrite && <Button onClick={() => { reset({ contractId: contractsQ.data?.content?.[0]?.id ?? "", changeType: CHANGE_TYPES[0], description: "", effectiveFrom: new Date().toISOString().slice(0, 10), newValidTo: "", paymentTermOverride: "", services: [] }); setOpenCreate(true); }}>+ New Addendum</Button>}
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-1 lg:grid-cols-6 gap-2">
            <Select value={contractId} onChange={(e) => { setContractId(e.target.value); setPage(0); }}>
              <option value="">Contract: All</option>{(contractsQ.data?.content ?? []).map((c) => <option key={c.id} value={c.id}>{c.contractNo} · {c.customerName}</option>)}
            </Select>
            <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
              <option value="">Status: All</option>{["DRAFT","SUBMITTED","UNDER_REVIEW","APPROVED","ACTIVE","REJECTED","REVISION_REQUESTED","CANCELLED"].map((s) => <option key={s} value={s}>{s}</option>)}
            </Select>
            <Select value={changeType} onChange={(e) => { setChangeType(e.target.value); setPage(0); }}>
              <option value="">Change: All</option>{CHANGE_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </Select>
            <Input placeholder="Search no/description..." value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
            <div className="flex gap-1">
              <Button variant="outline" size="sm" onClick={() => { setContractId(""); setStatus(""); setChangeType(""); setQ(""); setPage(0); }}>Clear</Button>
            </div>
          </div>
          {listQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : listQ.isError ? <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed")}</div> : <DataTable columns={columns} data={items} emptyMessage="No addenda" />}
          <div className="flex gap-2 text-sm">
            <Button size="sm" variant="outline" disabled={page===0} onClick={() => setPage((p)=>Math.max(0,p-1))}>Previous</Button>
            <span className="py-1 text-xs text-muted-foreground">Page {page+1} · {listQ.data?.totalPages ?? 1}</span>
            <Button size="sm" variant="outline" disabled={!listQ.data || page+1 >= (listQ.data.totalPages ?? 1)} onClick={() => setPage((p)=>p+1)}>Next</Button>
          </div>
        </CardContent>
      </Card>

      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-auto">
          <DialogHeader><DialogTitle>Create addendum</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMut.mutate(d))} className="space-y-3">
            <div><Label>Contract *</Label><Select {...register("contractId")}><option value="">Select</option>{(contractsQ.data?.content ?? []).map((c) => <option key={c.id} value={c.id}>{c.contractNo} [{c.status}]</option>)}</Select>{errors.contractId && <p className="text-xs text-destructive">{errors.contractId.message}</p>}</div>
            <div><Label>Change type *</Label><Select {...register("changeType")}>{CHANGE_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}</Select></div>
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

      <Dialog open={!!editId} onOpenChange={(o)=>!o && setEditId(null)}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-auto">
          <DialogHeader><DialogTitle>Edit addendum</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d)=>updateMut.mutate(d))} className="space-y-3">
            <div><Label>Contract *</Label><Select {...register("contractId")}><option value="">Select</option>{(contractsQ.data?.content ?? []).map((c)=><option key={c.id} value={c.id}>{c.contractNo}</option>)}</Select></div>
            <div><Label>Change type *</Label><Select {...register("changeType")}>{CHANGE_TYPES.map(t=><option key={t} value={t}>{t}</option>)}</Select></div>
            <div><Label>Description</Label><Textarea {...register("description")} /></div>
            <div className="grid grid-cols-2 gap-2"><div><Label>Effective from *</Label><Input type="date" {...register("effectiveFrom")} /></div>{watchedType==="TERM_EXTENSION" && <div><Label>New valid to</Label><Input type="date" {...register("newValidTo")} /></div>}{watchedType==="PAYMENT_TERMS" && <div><Label>Payment term override</Label><Input {...register("paymentTermOverride")} /></div>}</div>
            {watchedType==="ADDED_SERVICE" && <div><Label>Services</Label><div className="space-y-1 border rounded p-2">{fields.map((f,i)=><div key={f.id} className="grid grid-cols-12 gap-1"><div className="col-span-3"><Input {...register(`services.${i}.serviceCode` as const)} placeholder="Code" /></div><div className="col-span-4"><Input {...register(`services.${i}.serviceName` as const)} placeholder="Name" /></div><div className="col-span-2"><Input {...register(`services.${i}.unit` as const)} placeholder="Unit" /></div><div className="col-span-2"><Input {...register(`services.${i}.scopeNote` as const)} placeholder="Scope" /></div><Button type="button" variant="ghost" size="sm" className="col-span-1" onClick={()=>remove(i)}>×</Button></div>)}<Button type="button" size="sm" variant="outline" onClick={()=>append({serviceCode:"",serviceName:"",unit:"",scopeNote:""})}>+ Service</Button></div></div>}
            {updateMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(updateMut.error, "Update failed")}</div>}
            <DialogFooter><Button type="button" variant="outline" onClick={()=>setEditId(null)}>Cancel</Button><Button type="submit" disabled={updateMut.isPending}>{updateMut.isPending?"Saving...":"Save"}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
