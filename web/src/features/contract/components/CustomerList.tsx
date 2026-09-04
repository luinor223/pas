import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, useMemo } from "react";
import { customersQuery, customerQuery } from "../hooks/contractQueries";
import { contractApi } from "../services/contractApi";
import type { CustomerResponse } from "../types/contractTypes";
import { Button } from "@/shared/components/button";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Textarea } from "@/shared/components/textarea";
import { StatusBadge } from "@/shared/components/status-badge";
import { DataTable } from "@/shared/components/data-table";
import type { CellContext, ColumnDef } from "@tanstack/react-table";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/shared/components/dialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { useForm, useFieldArray } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { getApiErrorMessage } from "@/shared/api/errors";
import { DEFAULT_PAGE_SIZE } from "@/shared/api/paging";
import { PaginationControls } from "@/shared/components/pagination-controls";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { Link, useNavigate } from "@tanstack/react-router";
import { RowMenu } from "@/shared/components/row-menu";
import { FilterBar } from "@/shared/components/filter-bar";
import { SearchInput } from "@/shared/components/search-input";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { ContactTable } from "./ContactTable";
import { statusLabel } from "@/shared/lib/labels";

const PAGE_SIZE = DEFAULT_PAGE_SIZE;

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
  const navigate = useNavigate();
  const canRead = useHasPermission("customer:read");
  const canReadContracts = useHasPermission("contract:read");
  const canWrite = useHasPermission("customer:write");
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("All");
  const [page, setPage] = useState(0);
  const [openCreate, setOpenCreate] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  // Customer id whose contacts are shown — the full record is fetched because
  // list rows only carry primaryContact (contacts: []).
  const [viewContactsId, setViewContactsId] = useState<string | null>(null);
  const [confirmSuspend, setConfirmSuspend] = useState<CustomerResponse | null>(null);

  const listQ = useQuery(customersQuery({ q: q || undefined, status: status === "All" ? undefined : status, page, size: PAGE_SIZE }));
  const viewContactsQ = useQuery({ ...customerQuery(viewContactsId ?? ""), enabled: !!viewContactsId });

  const customers = listQ.data?.content ?? [];
  const total = listQ.data?.totalElements ?? 0;



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
    mutationFn: ({ id, reason }: { id: string; reason?: string }) => contractApi.suspendCustomer(id, reason),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["customers"] }); setConfirmSuspend(null); },
  });
  const activateMut = useMutation({
    mutationFn: (id: string) => contractApi.activateCustomer(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["customers"] }),
  });

  const [editLoading, setEditLoading] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const toContactForm = (x: { fullName: string; title?: string | null; email?: string | null; phone?: string | null; primary: boolean }) =>
    ({ fullName: x.fullName, title: x.title ?? "", email: x.email ?? "", phone: x.phone ?? "", primary: x.primary });

  // List rows carry contacts: [] (primaryContact only) — always load the full
  // customer before editing, or saving would silently wipe existing contacts.
  const onEdit = async (c: CustomerResponse) => {
    setEditId(c.id);
    setEditError(null);
    setEditLoading(true);
    try {
      const full = await contractApi.getCustomer(c.id);
      reset({
        name: full.name, shortName: full.shortName ?? "", taxCode: full.taxCode ?? "", address: full.address ?? "",
        representativeName: full.representativeName ?? "", representativePosition: full.representativePosition ?? "", segment: full.segment ?? "",
        contacts: full.contacts.length ? full.contacts.map(toContactForm) : [{ fullName: "", title: "", email: "", phone: "", primary: true }],
      });
    } catch (e: unknown) {
      setEditError(getApiErrorMessage(e, "Failed to load customer details"));
      reset({
        name: c.name, shortName: c.shortName ?? "", taxCode: c.taxCode ?? "", address: c.address ?? "",
        representativeName: c.representativeName ?? "", representativePosition: c.representativePosition ?? "", segment: c.segment ?? "",
        contacts: [{ fullName: "", title: "", email: "", phone: "", primary: true }],
      });
    } finally {
      setEditLoading(false);
    }
  };

  const columns = useMemo<ColumnDef<CustomerResponse>[]>(() => [
    {
      accessorKey: "code", header: "CODE",
      cell: ({ row }) => <Link to="/customers" search={{ id: row.original.id } as never} className="font-medium text-blue-600 hover:underline">{row.original.code}</Link>,
    },
    { accessorKey: "name", header: "CUSTOMER NAME", cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: "taxCode", header: "TAX ID", cell: ({ row }) => <span className="text-sm">{row.original.taxCode ?? "—"}</span> },
    { accessorKey: "representativeName", header: "REPRESENTATIVE", cell: ({ row }) => <span className="text-sm">{row.original.representativeName ?? "—"}</span> },
    {
      id: "contact", header: "CONTACT",
      cell: ({ row }) => <span className="text-sm text-muted-foreground">{row.original.primaryContact?.email ?? "—"}</span>,
    },
    ...(canReadContracts ? [{
      id: "contracts", header: "CONTRACTS",
      cell: ({ row }: CellContext<CustomerResponse, unknown>) => <span className="tabular-nums">{row.original.contractsCount}</span>,
    }] : []),
    { accessorKey: "status", header: "STATUS", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      id: "actions", header: "ACTION", enableSorting: false,
      cell: ({ row }) => {
        const c = row.original;
        const items: { label: string; onClick: () => void; danger?: boolean }[] = [
          { label: "View details", onClick: () => navigate({ to: "/customers", search: { id: c.id } as never }) },
          { label: "View contacts", onClick: () => setViewContactsId(c.id) },
        ];
        if (canWrite) items.push({ label: "Edit", onClick: () => onEdit(c) });
        if (canWrite && c.status === "ACTIVE") items.push({ label: "Suspend", onClick: () => setConfirmSuspend(c), danger: true });
        if (canWrite && c.status !== "ACTIVE") items.push({ label: "Activate", onClick: () => activateMut.mutate(c.id) });
        if (canReadContracts) items.push({ label: "View contracts", onClick: () => navigate({ to: "/contracts", search: { customerId: c.id } as never }) });
        return (
          <div className="text-right">
            <RowMenu items={items} />
          </div>
        );
      },
    },
  ], [canReadContracts, canWrite, navigate]);

  if (!canRead) return <Card><CardContent className="p-6 text-sm">You do not have access to customers.</CardContent></Card>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <CardTitle>Customers ({total})</CardTitle>
          <div className="flex items-center gap-2">
            <SearchInput
              className="w-56 lg:w-72"
              label="Search customers"
              placeholder="Search code/name/tax..."
              value={q}
              onChange={(value) => { setQ(value); setPage(0); }}
            />
          {canWrite && <Button onClick={() => { reset({ name: "", shortName: "", taxCode: "", address: "", representativeName: "", representativePosition: "", segment: "", contacts: [{ fullName: "", title: "", email: "", phone: "", primary: true }] }); setOpenCreate(true); }}>+ New Customer</Button>}
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <FilterBar>
            <Select className="w-full sm:w-48" aria-label="Filter by status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
              <option value="All">Status: All</option><option value="ACTIVE">{statusLabel("ACTIVE")}</option><option value="SUSPENDED">{statusLabel("SUSPENDED")}</option>
            </Select>
          </FilterBar>
          {listQ.isLoading ? <div className="text-sm text-muted-foreground">Loading...</div> : listQ.isError ? <div className="text-sm text-destructive">{getApiErrorMessage(listQ.error, "Failed")}</div> : <DataTable columns={columns} data={customers} emptyMessage="No customers" pageSize={PAGE_SIZE} />}
          <PaginationControls page={page} totalPages={listQ.data?.totalPages ?? 1} pageSize={PAGE_SIZE} totalItems={total} onPageChange={setPage} />
        </CardContent>
      </Card>

      <ConfirmDialog
        open={!!confirmSuspend}
        title="Suspend this customer?"
        body={
          <>
            <p>
              <span className="font-medium">{confirmSuspend?.name}</span>{" "}
              ({confirmSuspend?.code}) will be suspended.
            </p>
            <p className="mt-2 text-muted-foreground">
              New contracts cannot be created for a suspended customer. Existing contracts are not
              affected, and you can reactivate them later.
            </p>
          </>
        }
        confirmLabel="Suspend customer"
        pendingLabel="Suspending..."
        pending={suspendMut.isPending}
        error={suspendMut.isError ? suspendMut.error : undefined}
        reason={{ label: "Reason", placeholder: "Why is this customer being suspended?", required: true }}
        onConfirm={(reason) => confirmSuspend && suspendMut.mutate({ id: confirmSuspend.id, reason })}
        onCancel={() => setConfirmSuspend(null)}
      />

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
                {fields.map((f, i) => {
                  const ce = (errors.contacts?.[i] ?? {}) as { fullName?: { message?: string }; email?: { message?: string } };
                  return (
                  <div key={f.id} className="rounded border p-2 space-y-2">
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2">
                      <div><Label className="text-xs">Full name *</Label><Input placeholder="Full name" {...register(`contacts.${i}.fullName` as const)} />{ce.fullName && <p className="text-xs text-destructive">{ce.fullName.message}</p>}</div>
                      <div><Label className="text-xs">Title</Label><Input placeholder="Title" {...register(`contacts.${i}.title` as const)} /></div>
                      <div><Label className="text-xs">Email</Label><Input placeholder="Email" {...register(`contacts.${i}.email` as const)} />{ce.email && <p className="text-xs text-destructive">{ce.email.message ?? "Invalid email"}</p>}</div>
                      <div><Label className="text-xs">Phone</Label><Input placeholder="Phone" {...register(`contacts.${i}.phone` as const)} /></div>
                    </div>
                    <div className="flex items-center justify-between">
                      <label className="flex items-center gap-2 text-xs cursor-pointer select-none">
                        <input type="checkbox" className="h-4 w-4 accent-primary" {...register(`contacts.${i}.primary` as const)} />
                        primary
                      </label>
                      <Button type="button" variant="ghost" size="sm" onClick={() => remove(i)} title="Remove contact">Remove</Button>
                    </div>
                  </div>
                  );
                })}
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
                {editLoading ? <div className="text-sm text-muted-foreground">Loading contacts...</div> : fields.map((f, i) => {
                  const ce = (errors.contacts?.[i] ?? {}) as { fullName?: { message?: string }; email?: { message?: string } };
                  return (
                  <div key={f.id} className="rounded border p-2 space-y-2">
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2">
                      <div><Label className="text-xs">Full name *</Label><Input {...register(`contacts.${i}.fullName` as const)} placeholder="Full name" />{ce.fullName && <p className="text-xs text-destructive">{ce.fullName.message}</p>}</div>
                      <div><Label className="text-xs">Title</Label><Input {...register(`contacts.${i}.title` as const)} placeholder="Title" /></div>
                      <div><Label className="text-xs">Email</Label><Input {...register(`contacts.${i}.email` as const)} placeholder="Email" />{ce.email && <p className="text-xs text-destructive">{ce.email.message ?? "Invalid email"}</p>}</div>
                      <div><Label className="text-xs">Phone</Label><Input {...register(`contacts.${i}.phone` as const)} placeholder="Phone" /></div>
                    </div>
                    <div className="flex items-center justify-between">
                      <label className="flex items-center gap-2 text-xs cursor-pointer select-none">
                        <input type="checkbox" className="h-4 w-4 accent-primary" {...register(`contacts.${i}.primary` as const)} />
                        primary
                      </label>
                      <Button type="button" variant="ghost" size="sm" onClick={() => remove(i)} title="Remove contact">Remove</Button>
                    </div>
                  </div>
                  );
                })}
                {editError && <div className="text-sm text-destructive">{editError}</div>}
                <Button type="button" variant="outline" size="sm" onClick={() => append({ fullName: "", title: "", email: "", phone: "", primary: false })}>+ Add</Button>
              </div>
            </div>
            {updateMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(updateMut.error, "Update failed")}</div>}
            <DialogFooter><Button type="button" variant="outline" onClick={() => setEditId(null)}>Cancel</Button><Button type="submit" disabled={updateMut.isPending}>{updateMut.isPending ? "Saving..." : "Save"}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={!!viewContactsId} onOpenChange={(o) => !o && setViewContactsId(null)}>
        <DialogContent className="max-w-3xl w-[calc(100vw-2rem)] max-h-[90vh] overflow-auto">
          <DialogHeader><DialogTitle>{viewContactsQ.data?.name ?? "Customer"} · Contacts</DialogTitle></DialogHeader>
          {viewContactsQ.isLoading ? (
            <div className="text-sm text-muted-foreground">Loading contacts...</div>
          ) : viewContactsQ.isError ? (
            <div className="text-sm text-destructive">{getApiErrorMessage(viewContactsQ.error, "Failed to load contacts")}</div>
          ) : (
            <ContactTable contacts={viewContactsQ.data?.contacts ?? []} />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
