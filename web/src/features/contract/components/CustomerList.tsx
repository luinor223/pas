import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, useMemo } from "react";
import { customersQuery, contractsQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import type { CustomerResponse } from "../types/contractTypes";
import { Button } from "@/shared/components/button";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Textarea } from "@/shared/components/textarea";
import { Badge } from "@/shared/components/badge";
import { StatusBadge } from "@/shared/components/status-badge";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/shared/components/dialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { useForm, useFieldArray } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";

const contactSchema = z.object({
  fullName: z.string().min(1, "Required"),
  title: z.string().optional().nullable(),
  email: z.string().email().optional().or(z.literal("")).nullable(),
  phone: z.string().optional().nullable(),
  primary: z.boolean(),
});

const schema = z.object({
  name: z.string().min(1, "Required"),
  shortName: z.string().optional().nullable(),
  taxCode: z.string().optional().nullable(),
  address: z.string().optional().nullable(),
  representativeName: z.string().optional().nullable(),
  representativePosition: z.string().optional().nullable(),
  segment: z.string().optional().nullable(),
  contacts: z.array(contactSchema).min(0),
}).refine((d) => d.contacts.filter((c) => c.primary).length <= 1, { message: "At most one primary", path: ["contacts"] });

type FormData = z.infer<typeof schema>;

export function CustomerList() {
  const qc = useQueryClient();
  const canRead = useHasPermission("customer:read");
  const canWrite = useHasPermission("customer:write");
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("All");
  const [page, setPage] = useState(0);
  const [openCreate, setOpenCreate] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [viewContacts, setViewContacts] = useState<CustomerResponse | null>(null);

  const listQ = useQuery(customersQuery({ q: q || undefined, status: status === "All" ? undefined : status, page, size: 25 }));
  const contractsQ = useQuery(contractsQuery({ size: 1000 }));

  const customers = listQ.data?.content ?? [];
  const total = listQ.data?.totalElements ?? 0;

  const contractsByCustomer = useMemo(() => {
    const map = new Map<string, number>();
    (contractsQ.data?.content ?? []).forEach((c) => map.set(c.customerId, (map.get(c.customerId) ?? 0) + 1));
    return map;
  }, [contractsQ.data]);

  const [openMenuId, setOpenMenuId] = useState<string | null>(null);

  const { register, handleSubmit, control, reset, formState: { errors } } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { name: "", shortName: "", taxCode: "", address: "", representativeName: "", representativePosition: "", segment: "", contacts: [{ fullName: "", title: "", email: "", phone: "", primary: true }] },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "contacts" });

  const createMut = useMutation({
    mutationFn: (data: FormData) => contractApi.createCustomer({
      name: data.name, shortName: data.shortName || null, taxCode: data.taxCode || null, address: data.address || null,
      representativeName: data.representativeName || null, representativePosition: data.representativePosition || null, segment: data.segment || null,
      contacts: data.contacts.map((c) => ({ fullName: c.fullName, title: c.title || null, email: c.email || null, phone: c.phone || null, primary: c.primary })),
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["customers"] }); setOpenCreate(false); reset(); },
  });

  const updateMut = useMutation({
    mutationFn: (data: FormData) => {
      if (!editId) throw new Error("No edit id");
      return contractApi.updateCustomer(editId, {
        name: data.name, shortName: data.shortName || null, taxCode: data.taxCode || null, address: data.address || null,
        representativeName: data.representativeName || null, representativePosition: data.representativePosition || null, segment: data.segment || null,
        contacts: data.contacts.map((c) => ({ fullName: c.fullName, title: c.title || null, email: c.email || null, phone: c.phone || null, primary: c.primary })),
      });
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["customers"] }); setEditId(null); },
  });

  const suspendMut = useMutation({
    mutationFn: (id: string) => contractApi.suspendCustomer(id, "Suspended via UI"),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["customers"] }),
  });
  const activateMut = useMutation({
    mutationFn: (id: string) => contractApi.activateCustomer(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["customers"] }),
  });

  const onEdit = (c: CustomerResponse) => {
    setEditId(c.id);
    reset({
      name: c.name, shortName: c.shortName ?? "", taxCode: c.taxCode ?? "", address: c.address ?? "",
      representativeName: c.representativeName ?? "", representativePosition: c.representativePosition ?? "", segment: c.segment ?? "",
      contacts: c.contacts.length ? c.contacts.map((x) => ({ fullName: x.fullName, title: x.title ?? "", email: x.email ?? "", phone: x.phone ?? "", primary: x.primary })) : [{ fullName: "", title: "", email: "", phone: "", primary: true }],
    });
  };

  const columns = useMemo<ColumnDef<CustomerResponse>[]>(() => [
    {
      accessorKey: "code", header: "CODE",
      cell: ({ row }) => <a href={`/customers?id=${row.original.id}`} className="font-medium text-blue-600 hover:underline">{row.original.code}</a>,
    },
    { accessorKey: "name", header: "CUSTOMER NAME", cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: "taxCode", header: "TAX ID", cell: ({ row }) => <span className="text-sm">{row.original.taxCode ?? "—"}</span> },
    { accessorKey: "representativeName", header: "REPRESENTATIVE", cell: ({ row }) => <span className="text-sm">{row.original.representativeName ?? "—"}</span> },
    {
      id: "contact", header: "CONTACT",
      cell: ({ row }) => <span className="text-sm text-muted-foreground">{row.original.primaryContact?.email ?? "—"}</span>,
    },
    {
      id: "contracts", header: "CONTRACTS",
      cell: ({ row }) => <span className="tabular-nums">{contractsByCustomer.get(row.original.id) ?? "—"}</span>,
    },
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      id: "actions", header: "", enableSorting: false,
      cell: ({ row }) => {
        const c = row.original;
        const open = openMenuId === c.id;
        return (
          <div className="relative text-right">
            <Button size="sm" variant="ghost" onClick={() => setOpenMenuId(open ? null : c.id)} title="Row actions">...</Button>
            {open && (
              <div className="absolute right-0 z-10 w-44 rounded-md border bg-white shadow-lg text-left text-sm" onMouseLeave={() => setOpenMenuId(null)}>
                <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); window.location.href = `/customers?id=${c.id}`; }}>View details</button>
                <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); setViewContacts(c); }}>View contacts</button>
                {canWrite && <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); onEdit(c); }}>Edit</button>}
                {canWrite && c.status === "ACTIVE" && <button className="block w-full px-3 py-2 hover:bg-muted text-left text-destructive" onClick={() => { setOpenMenuId(null); suspendMut.mutate(c.id); }}>Suspend</button>}
                {canWrite && c.status !== "ACTIVE" && <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); activateMut.mutate(c.id); }}>Activate</button>}
                <button className="block w-full px-3 py-2 hover:bg-muted text-left" onClick={() => { setOpenMenuId(null); window.location.href = `/contracts?customerId=${c.id}`; }}>View contracts</button>
              </div>
            )}
          </div>
        );
      },
    },
  ], [canWrite, contractsByCustomer, openMenuId]);

  if (!canRead) return <Card><CardContent className="p-6 text-sm">You need <code>customer:read</code> to view this page.</CardContent></Card>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Customers ({total})</CardTitle>
          {canWrite && <Button onClick={() => { reset({ name: "", shortName: "", taxCode: "", address: "", representativeName: "", representativePosition: "", segment: "", contacts: [{ fullName: "", title: "", email: "", phone: "", primary: true }] }); setOpenCreate(true); }}>+ New Customer</Button>}
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex gap-2">
            <Input placeholder="Search code/name/tax..." value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} className="max-w-sm" />
            <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
              <option value="All">Status: All</option><option value="ACTIVE">ACTIVE</option><option value="SUSPENDED">SUSPENDED</option>
            </Select>
          </div>
          {listQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : listQ.isError ? <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed")}</div> : <DataTable columns={columns} data={customers} emptyMessage="No customers" pageSize={25} />}
          <div className="flex items-center justify-between text-sm">
            <span className="text-xs text-muted-foreground">Rows per page: 25</span>
            <div className="flex gap-2 items-center">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</Button>
              <span className="py-1 text-xs text-muted-foreground">Page {page + 1} · {listQ.data?.totalPages ?? 1} pages</span>
              <Button size="sm" variant="outline" disabled={!listQ.data || page + 1 >= (listQ.data?.totalPages ?? 1)} onClick={() => setPage((p) => p + 1)}>Next</Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-auto">
          <DialogHeader><DialogTitle>Create customer</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMut.mutate(d))} className="space-y-3">
            <div><Label>Name *</Label><Input {...register("name")} />{errors.name && <p className="text-xs text-destructive">{errors.name.message}</p>}</div>
            <div className="grid grid-cols-2 gap-2"><div><Label>Short name</Label><Input {...register("shortName")} /></div><div><Label>Tax code</Label><Input {...register("taxCode")} /></div></div>
            <div><Label>Address</Label><Textarea {...register("address")} /></div>
            <div className="grid grid-cols-2 gap-2"><div><Label>Representative</Label><Input {...register("representativeName")} /></div><div><Label>Position</Label><Input {...register("representativePosition")} /></div></div>
            <div><Label>Segment</Label><Input {...register("segment")} placeholder="e.g. RETAIL" /></div>
            <div>
              <Label>Contacts</Label>
              <div className="space-y-2 border rounded p-2">
                {fields.map((f, i) => (
                  <div key={f.id} className="grid grid-cols-12 gap-1 items-end border-b pb-2">
                    <div className="col-span-3"><Label className="text-xs">Full name *</Label><Input {...register(`contacts.${i}.fullName` as const)} /></div>
                    <div className="col-span-2"><Label className="text-xs">Title</Label><Input {...register(`contacts.${i}.title` as const)} /></div>
                    <div className="col-span-3"><Label className="text-xs">Email</Label><Input {...register(`contacts.${i}.email` as const)} /></div>
                    <div className="col-span-2"><Label className="text-xs">Phone</Label><Input {...register(`contacts.${i}.phone` as const)} /></div>
                    <label className="col-span-1 flex items-center gap-1 text-xs"><input type="checkbox" {...register(`contacts.${i}.primary` as const)} /> primary</label>
                    <Button type="button" variant="ghost" size="sm" className="col-span-1" onClick={() => remove(i)}>×</Button>
                  </div>
                ))}
                <Button type="button" variant="outline" size="sm" onClick={() => append({ fullName: "", title: "", email: "", phone: "", primary: false })}>+ Add contact</Button>
                {errors.contacts && <p className="text-xs text-destructive">{String(errors.contacts.message)}</p>}
              </div>
            </div>
            {createMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(createMut.error, "Create failed")}</div>}
            <DialogFooter><Button type="button" variant="outline" onClick={() => setOpenCreate(false)}>Cancel</Button><Button type="submit" disabled={createMut.isPending}>{createMut.isPending ? "Creating..." : "Create"}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={!!editId} onOpenChange={(o) => !o && setEditId(null)}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-auto">
          <DialogHeader><DialogTitle>Edit customer</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => updateMut.mutate(d))} className="space-y-3">
            <div><Label>Name *</Label><Input {...register("name")} />{errors.name && <p className="text-xs text-destructive">{errors.name.message}</p>}</div>
            <div className="grid grid-cols-2 gap-2"><div><Label>Short name</Label><Input {...register("shortName")} /></div><div><Label>Tax code</Label><Input {...register("taxCode")} /></div></div>
            <div><Label>Address</Label><Textarea {...register("address")} /></div>
            <div className="grid grid-cols-2 gap-2"><div><Label>Representative</Label><Input {...register("representativeName")} /></div><div><Label>Position</Label><Input {...register("representativePosition")} /></div></div>
            <div><Label>Segment</Label><Input {...register("segment")} /></div>
            <div>
              <Label>Contacts</Label>
              <div className="space-y-2 border rounded p-2">
                {fields.map((f, i) => (
                  <div key={f.id} className="grid grid-cols-12 gap-1 items-end border-b pb-2">
                    <div className="col-span-3"><Input {...register(`contacts.${i}.fullName` as const)} placeholder="Full name" /></div>
                    <div className="col-span-2"><Input {...register(`contacts.${i}.title` as const)} placeholder="Title" /></div>
                    <div className="col-span-3"><Input {...register(`contacts.${i}.email` as const)} placeholder="Email" /></div>
                    <div className="col-span-2"><Input {...register(`contacts.${i}.phone` as const)} placeholder="Phone" /></div>
                    <label className="col-span-1 flex items-center gap-1 text-xs"><input type="checkbox" {...register(`contacts.${i}.primary` as const)} /> primary</label>
                    <Button type="button" variant="ghost" size="sm" className="col-span-1" onClick={() => remove(i)}>×</Button>
                  </div>
                ))}
                <Button type="button" variant="outline" size="sm" onClick={() => append({ fullName: "", title: "", email: "", phone: "", primary: false })}>+ Add</Button>
              </div>
            </div>
            {updateMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(updateMut.error, "Update failed")}</div>}
            <DialogFooter><Button type="button" variant="outline" onClick={() => setEditId(null)}>Cancel</Button><Button type="submit" disabled={updateMut.isPending}>{updateMut.isPending ? "Saving..." : "Save"}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={!!viewContacts} onOpenChange={(o) => !o && setViewContacts(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>{viewContacts?.name} · Contacts</DialogTitle></DialogHeader>
          <div className="space-y-2 text-sm">
            {(viewContacts?.contacts ?? []).length === 0 ? <div className="text-muted-foreground">No contacts.</div> : viewContacts?.contacts.map((c) => (
              <div key={c.id} className="border rounded p-2 flex justify-between">
                <div><div className="font-medium">{c.fullName} {c.primary && <Badge variant="secondary" className="ml-1">primary</Badge>}</div><div className="text-xs text-muted-foreground">{c.title} · {c.email} · {c.phone}</div></div>
              </div>
            ))}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
