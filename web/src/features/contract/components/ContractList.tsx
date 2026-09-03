import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, useMemo } from "react";
import { contractsQuery, customersQuery } from "../hooks/contractQueries";
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
import { z } from "zod";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { Link, useNavigate } from "@tanstack/react-router";

const SERVICE_GROUPS = ["STEVEDORING", "WAREHOUSING", "TRANSPORTATION", "CONTAINER_HANDLING"];
const STATUSES = ["DRAFT", "SUBMITTED", "UNDER_REVIEW", "APPROVED", "ACTIVE", "EXPIRED", "REJECTED", "REVISION_REQUESTED", "CANCELLED"];

const schema = z.object({
  customerId: z.string().min(1, "Required"),
  description: z.string().optional().nullable(),
  serviceGroup: z.string().min(1),
  value: z.string().optional().nullable(),
  currency: z.string().optional().nullable(),
  validFrom: z.string().min(1, "Required"),
  validTo: z.string().min(1, "Required"),
  paymentTerm: z.string().optional().nullable(),
  billingCycle: z.string().optional().nullable(),
  vatRate: z.string().optional().nullable(),
  penaltyTerms: z.string().optional().nullable(),
  serviceClause: z.string().optional().nullable(),
}).refine((d) => !d.validFrom || !d.validTo || d.validFrom <= d.validTo, { message: "validFrom must not be after validTo", path: ["validTo"] })
  .refine((d) => d.value === null || d.value === undefined || d.value === "" || !isNaN(Number(d.value)), { message: "value must be a number", path: ["value"] })
  .refine((d) => d.vatRate === null || d.vatRate === undefined || d.vatRate === "" || (!isNaN(Number(d.vatRate)) && Number(d.vatRate) >= 0 && Number(d.vatRate) <= 100), { message: "vatRate 0..100", path: ["vatRate"] });

type FormData = z.infer<typeof schema>;

export function ContractList() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const canRead = useHasPermission("contract:read");
  const canWrite = useHasPermission("contract:write");
  const canEsign = useHasPermission("esign:send");
  const [q, setQ] = useState("");
  const [customerId, setCustomerId] = useState(() => new URLSearchParams(window.location.search).get("customerId") ?? "");
  const [status, setStatus] = useState("");
  const [serviceGroup, setServiceGroup] = useState("");
  const [validFromFrom, setValidFromFrom] = useState("");
  const [validToTo, setValidToTo] = useState("");
  const [page, setPage] = useState(0);
  const [openCreate, setOpenCreate] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [editVersion, setEditVersion] = useState<number | null>(null);

  const listParams = { q: q || undefined, customerId: customerId || undefined, status: status || undefined, serviceGroup: serviceGroup || undefined, validFromFrom: validFromFrom || undefined, validToTo: validToTo || undefined, page, size: 25 };
  const listQ = useQuery(contractsQuery(listParams));
  const customersQ = useQuery(customersQuery({ size: 100 }));

  const contracts = listQ.data?.content ?? [];
  const total = listQ.data?.totalElements ?? 0;

  const { register, handleSubmit, reset, formState: { errors } } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { customerId: "", description: "", serviceGroup: SERVICE_GROUPS[0], value: "", currency: "VND", validFrom: "", validTo: "", paymentTerm: "", billingCycle: "MONTHLY", vatRate: "", penaltyTerms: "", serviceClause: "" },
  });

  const toNum = (v: string | null | undefined) => (v === null || v === undefined || v === "" ? null : Number(v));

  const createMut = useMutation({
    mutationFn: (data: FormData) => contractApi.createContract({
      customerId: data.customerId, description: data.description || null, serviceGroup: data.serviceGroup, value: toNum(data.value), currency: data.currency || "VND",
      validFrom: data.validFrom, validTo: data.validTo, paymentTerm: data.paymentTerm || null, billingCycle: data.billingCycle || "MONTHLY", vatRate: toNum(data.vatRate), penaltyTerms: data.penaltyTerms || null, serviceClause: data.serviceClause || null,
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["contracts"] }); setOpenCreate(false); reset(); },
  });
  const updateMut = useMutation({
    mutationFn: (data: FormData) => {
      if (!editId) throw new Error("No edit");
      return contractApi.updateContract(editId, {
        customerId: data.customerId, description: data.description || null, serviceGroup: data.serviceGroup, value: toNum(data.value), currency: data.currency || "VND",
        validFrom: data.validFrom, validTo: data.validTo, paymentTerm: data.paymentTerm || null, billingCycle: data.billingCycle || "MONTHLY", vatRate: toNum(data.vatRate), penaltyTerms: data.penaltyTerms || null, serviceClause: data.serviceClause || null, version: editVersion ?? 0,
      });
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["contracts"] }); setEditId(null); },
  });
  const submitMut = useMutation({ mutationFn: (id: string) => contractApi.submitContract(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["contracts"] }) });
  const cancelMut = useMutation({ mutationFn: (id: string) => contractApi.cancelContract(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["contracts"] }) });
  const reviseMut = useMutation({ mutationFn: (id: string) => contractApi.reviseContract(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["contracts"] }) });
  const sendMut = useMutation({ mutationFn: (id: string) => contractApi.sendForSigningContract(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["contracts"] }) });

  const onEdit = (c: ContractResponse) => {
    setEditId(c.id); setEditVersion(c.version);
    reset({
      customerId: c.customerId, description: c.description ?? "", serviceGroup: c.serviceGroup, value: c.value != null ? String(c.value) : "", currency: c.currency, validFrom: c.validFrom, validTo: c.validTo, paymentTerm: c.paymentTerm ?? "", billingCycle: c.billingCycle, vatRate: c.vatRate != null ? String(c.vatRate) : "", penaltyTerms: c.penaltyTerms ?? "", serviceClause: c.serviceClause ?? "",
    });
  };

  const [openMenuId, setOpenMenuId] = useState<string | null>(null);

  const fmtDate = (iso: string) => {
    const [y, m, d] = iso.split("-");
    return y && m && d ? `${d}/${m}/${y}` : iso;
  };
  const fmtMoney = (v: number | null, cur: string) =>
    v == null ? "—" : `${v.toLocaleString("vi-VN")} ${cur === "VND" ? "" : cur}`.trim();

  const columns = useMemo<ColumnDef<ContractResponse>[]>(() => [
    {
      accessorKey: "contractNo", header: "CONTRACT NO.",
      cell: ({ row }) => <Link to="/contracts" search={{ id: row.original.id } as never} className="font-medium text-blue-600 hover:underline">{row.original.contractNo}</Link>,
    },
    { accessorKey: "customerName", header: "CUSTOMER", cell: ({ row }) => <span className="font-medium">{row.original.customerName}</span> },
    { accessorKey: "serviceGroup", header: "SERVICE GROUP", cell: ({ row }) => <span className="text-sm capitalize">{row.original.serviceGroup.toLowerCase().replace(/_/g, " ")}</span> },
    { accessorKey: "value", header: "VALUE (VND)", cell: ({ row }) => <span className="tabular-nums">{fmtMoney(row.original.value, row.original.currency)}</span> },
    { accessorKey: "validFrom", header: "EFFECTIVE", cell: ({ row }) => <span className="text-xs">{fmtDate(row.original.validFrom)}</span> },
    { accessorKey: "validTo", header: "EXPIRY", cell: ({ row }) => <span className="text-xs">{fmtDate(row.original.validTo)}</span> },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      id: "actions", header: "ACTION", enableSorting: false,
      cell: ({ row }) => {
        const c = row.original;
        const editable = c.status === "DRAFT" || c.status === "REVISION_REQUESTED";
        const open = openMenuId === c.id;
        return (
          <div className="relative text-right">
            <Button size="sm" variant="ghost" onClick={() => setOpenMenuId(open ? null : c.id)} title="Row actions">...</Button>
            {open && (
              <div className="absolute right-0 z-10 w-44 rounded-md border bg-white shadow-lg text-left text-sm" onMouseLeave={() => setOpenMenuId(null)}>
                <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); navigate({ to: "/contracts", search: { id: c.id } as never }); }}>View details</button>
                <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); navigate({ to: "/contracts", search: { id: c.id, tab: "attachments" } as never }); }}>Download</button>
                {canWrite && editable && <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); onEdit(c); }}>Edit</button>}
                {canWrite && c.status === "DRAFT" && <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); submitMut.mutate(c.id); }}>Submit for approval</button>}
                {canWrite && c.status === "REJECTED" && <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); reviseMut.mutate(c.id); }}>Revise</button>}
                {canWrite && <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); navigate({ to: "/addenda", search: { contractId: c.id } as never }); }}>Create addendum</button>}
                {canWrite && <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); navigate({ to: "/addenda", search: { contractId: c.id, changeType: "TERM_EXTENSION" } as never }); }}>Renew contract</button>}
                {canEsign && c.status === "APPROVED" && <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); sendMut.mutate(c.id); }}>Send for signing</button>}
                {canWrite && <button className="block w-full px-3 py-2 hover:bg-muted text-left text-destructive" onClick={() => { setOpenMenuId(null); cancelMut.mutate(c.id); }}>Cancel contract</button>}
              </div>
            )}
          </div>
        );
      },
    },
  ], [canWrite, canEsign, openMenuId]);

  if (!canRead) return <Card><CardContent className="p-6 text-sm">Need <code>contract:read</code></CardContent></Card>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Contracts ({total})</CardTitle>
          {canWrite && <Button onClick={() => { reset({ customerId: customersQ.data?.content?.[0]?.id ?? "", description: "", serviceGroup: SERVICE_GROUPS[0], value: "", currency: "VND", validFrom: new Date().toISOString().slice(0, 10), validTo: new Date(Date.now() + 30*24*3600*1000).toISOString().slice(0, 10), paymentTerm: "", billingCycle: "MONTHLY", vatRate: "", penaltyTerms: "", serviceClause: "" }); setOpenCreate(true); }}>+ New Contract</Button>}
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-1 lg:grid-cols-7 gap-2">
            <Input placeholder="Search no/description/customer..." value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
            <Select value={customerId} onChange={(e) => { setCustomerId(e.target.value); setPage(0); }}>
              <option value="">Customer: All</option>
              {(customersQ.data?.content ?? []).map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}
            </Select>
            <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
              <option value="">Status: All</option>{STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
            </Select>
            <Select value={serviceGroup} onChange={(e) => { setServiceGroup(e.target.value); setPage(0); }}>
              <option value="">Group: All</option>{SERVICE_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
            </Select>
            <Input type="date" value={validFromFrom} onChange={(e) => { setValidFromFrom(e.target.value); setPage(0); }} placeholder="Valid from ≥" />
            <Input type="date" value={validToTo} onChange={(e) => { setValidToTo(e.target.value); setPage(0); }} placeholder="Valid to ≤" />
            <Button variant="outline" onClick={() => { setQ(""); setCustomerId(""); setStatus(""); setServiceGroup(""); setValidFromFrom(""); setValidToTo(""); setPage(0); }}>Clear</Button>
          </div>
          {listQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : listQ.isError ? <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed")}</div> : <DataTable columns={columns} data={contracts} emptyMessage="No contracts" pageSize={25} />}
          <div className="flex items-center justify-between text-sm">
            <span className="text-xs text-muted-foreground">Rows per page: 25</span>
            <div className="flex gap-2 items-center">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</Button>
              <span className="py-1 text-xs text-muted-foreground">Page {page + 1} · {listQ.data?.totalPages ?? 1}</span>
              <Button size="sm" variant="outline" disabled={!listQ.data || page + 1 >= (listQ.data.totalPages ?? 1)} onClick={() => setPage((p) => p + 1)}>Next</Button>
            </div>
          </div>
          {(submitMut.isError || cancelMut.isError || reviseMut.isError || sendMut.isError) && <div className="text-xs text-destructive">{getApiErrorMessage((submitMut.error ?? cancelMut.error ?? reviseMut.error ?? sendMut.error) as unknown as Error, "Action failed")}</div>}
        </CardContent>
      </Card>

      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-auto">
          <DialogHeader><DialogTitle>Create contract</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMut.mutate(d))} className="space-y-3">
            <div><Label>Customer *</Label><Select {...register("customerId")}><option value="">Select</option>{(customersQ.data?.content ?? []).map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}</Select>{errors.customerId && <p className="text-xs text-destructive">{errors.customerId.message}</p>}</div>
            <div><Label>Description</Label><Textarea {...register("description")} /></div>
            <div className="grid grid-cols-2 gap-2"><div><Label>Service group *</Label><Select {...register("serviceGroup")}>{SERVICE_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}</Select></div><div><Label>Currency</Label><Input {...register("currency")} placeholder="VND" /></div></div>
            <div className="grid grid-cols-3 gap-2"><div><Label>Value</Label><Input type="number" step="0.01" {...register("value")} /></div><div><Label>Valid from *</Label><Input type="date" {...register("validFrom")} />{errors.validFrom && <p className="text-xs text-destructive">{errors.validFrom.message}</p>}</div><div><Label>Valid to *</Label><Input type="date" {...register("validTo")} />{errors.validTo && <p className="text-xs text-destructive">{errors.validTo.message}</p>}</div></div>
            <div className="grid grid-cols-3 gap-2"><div><Label>Payment term</Label><Input {...register("paymentTerm")} placeholder="e.g. 30D" /></div><div><Label>Billing cycle</Label><Input {...register("billingCycle")} readOnly /></div><div><Label>VAT rate</Label><Input type="number" step="0.01" {...register("vatRate")} /></div></div>
            <div><Label>Penalty terms</Label><Textarea {...register("penaltyTerms")} /></div>
            <div><Label>Service clause</Label><Textarea {...register("serviceClause")} /></div>
            {createMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(createMut.error, "Create failed")}</div>}
            <DialogFooter><Button type="button" variant="outline" onClick={() => setOpenCreate(false)}>Cancel</Button><Button type="submit" disabled={createMut.isPending}>{createMut.isPending ? "Creating..." : "Create"}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={!!editId} onOpenChange={(o) => !o && setEditId(null)}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-auto">
          <DialogHeader><DialogTitle>Edit contract</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => updateMut.mutate(d))} className="space-y-3">
            <div><Label>Customer *</Label><Select {...register("customerId")}><option value="">Select</option>{(customersQ.data?.content ?? []).map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}</Select></div>
            <div><Label>Description</Label><Textarea {...register("description")} /></div>
            <div className="grid grid-cols-2 gap-2"><div><Label>Service group *</Label><Select {...register("serviceGroup")}>{SERVICE_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}</Select></div><div><Label>Currency</Label><Input {...register("currency")} /></div></div>
            <div className="grid grid-cols-3 gap-2"><div><Label>Value</Label><Input type="number" step="0.01" {...register("value")} /></div><div><Label>Valid from *</Label><Input type="date" {...register("validFrom")} /></div><div><Label>Valid to *</Label><Input type="date" {...register("validTo")} /></div></div>
            <div className="grid grid-cols-3 gap-2"><div><Label>Payment term</Label><Input {...register("paymentTerm")} /></div><div><Label>Billing cycle</Label><Input {...register("billingCycle")} readOnly /></div><div><Label>VAT rate</Label><Input type="number" step="0.01" {...register("vatRate")} /></div></div>
            <div><Label>Penalty terms</Label><Textarea {...register("penaltyTerms")} /></div>
            <div><Label>Service clause</Label><Textarea {...register("serviceClause")} /></div>
            {updateMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(updateMut.error, "Update failed")}</div>}
            <DialogFooter><Button type="button" variant="outline" onClick={() => setEditId(null)}>Cancel</Button><Button type="submit" disabled={updateMut.isPending}>{updateMut.isPending ? "Saving..." : "Save"}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
