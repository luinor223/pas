import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, useMemo, useRef, useEffect } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { workflowApi } from "../services/workflowApi";
import { definitionsQuery, documentTypesQuery } from "../hooks/workflowQueries";
import { rolesQuery } from "@/features/admin/hooks/adminQueries";
import type { StepRequest, WorkflowDefinitionResponse } from "../types/workflowTypes";
import { Button } from "@/shared/components/button";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Badge } from "@/shared/components/badge";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/shared/components/dialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { FilterBar } from "@/shared/components/filter-bar";
import { RowMenu } from "@/shared/components/row-menu";
import { ConfirmDialog } from "@/shared/components/confirm-dialog";
import { Forbidden } from "@/shared/components/Forbidden";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { getApiErrorMessage } from "@/shared/api/errors";
import { roleLabel } from "@/shared/lib/labels";
import { formatDateTime } from "@/shared/lib/format";

const createSchema = z.object({
  documentTypeCode: z.string().min(1, "Required"),
  name: z.string().min(1, "Required"),
});
type FormCreate = z.infer<typeof createSchema>;

const stepSchema = z.object({
  name: z.string().min(1, "Required"),
  approverRole: z.string().min(1, "Required"),
  slaHours: z.coerce.number().int().min(1, "Min 1 hour"),
});

function emptyStep(defaultRole: string): StepRequest {
  return { name: "", approverRole: defaultRole, slaHours: 48 };
}

export function WorkflowDefinitions() {
  const qc = useQueryClient();
  const canConfigure = useHasPermission("workflow:configure");
  const [docType, setDocType] = useState("All");
  const [openCreate, setOpenCreate] = useState(false);
  // Wizard step 2: edit steps of this definition right after creation (or via Edit action).
  const [editStepsId, setEditStepsId] = useState<string | null>(null);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [confirmActivate, setConfirmActivate] = useState<WorkflowDefinitionResponse | null>(null);

  const docTypesQ = useQuery(documentTypesQuery);
  const defsQ = useQuery(definitionsQuery(docType === "All" ? undefined : docType));
  const rolesQ = useQuery(rolesQuery);

  const defs = useMemo(
    () => [...(defsQ.data ?? [])].sort((a, b) => b.versionNo - a.versionNo),
    [defsQ.data],
  );
  const editDef = defs.find((d) => d.id === editStepsId) ?? null;
  const detailDef = defs.find((d) => d.id === detailId) ?? null;

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["workflow-definitions"] });
  };

  const createMut = useMutation({
    mutationFn: (data: FormCreate) => workflowApi.createDefinition(data),
    onSuccess: (created) => {
      invalidate();
      setOpenCreate(false);
      resetCreate();
      // Wizard step 2: continue straight into editing steps of the new draft.
      setEditStepsId(created.id);
    },
  });

  const activateMut = useMutation({
    mutationFn: (id: string) => workflowApi.activateDefinition(id),
    onSuccess: () => {
      invalidate();
      setConfirmActivate(null);
    },
  });

  const { register, handleSubmit, reset: resetCreate, formState: { errors } } = useForm<FormCreate>({
    resolver: zodResolver(createSchema),
    defaultValues: { documentTypeCode: "", name: "" },
  });

  // Preselect the doc-type filter in the wizard. Reset on the open transition
  // (after mount) — same pattern as UserList's edit dialog.
  const wasOpenCreate = useRef(false);
  useEffect(() => {
    if (openCreate && !wasOpenCreate.current) {
      resetCreate({ documentTypeCode: docType === "All" ? "" : docType, name: "" });
    }
    wasOpenCreate.current = openCreate;
  }, [openCreate, docType, resetCreate]);

  const columns = useMemo<ColumnDef<WorkflowDefinitionResponse>[]>(() => [
    {
      accessorKey: "versionNo",
      header: "VERSION",
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <span className="font-mono font-medium">v{row.original.versionNo}</span>
          {row.original.active
            ? <Badge className="bg-green-100 text-green-800">Active</Badge>
            : <Badge variant="secondary">Draft</Badge>}
        </div>
      ),
    },
    {
      accessorKey: "name",
      header: "NAME",
      cell: ({ row }) => (
        <div>
          <div className="font-medium">{row.original.name}</div>
          <div className="text-xs text-muted-foreground">{row.original.documentTypeName} · {row.original.steps.length} steps</div>
        </div>
      ),
    },
    {
      id: "chain",
      header: "CHAIN",
      enableSorting: false,
      cell: ({ row }) => (
        <span className="text-xs text-muted-foreground">
          {row.original.steps.map((s) => roleLabel(s.approverRole)).join(" → ") || "No steps yet"}
        </span>
      ),
    },
    {
      accessorKey: "createdAt",
      header: "CREATED",
      cell: ({ row }) => <span className="text-xs">{formatDateTime(row.original.createdAt)}</span>,
    },
    {
      id: "actions",
      header: () => <span className="sr-only">Actions</span>,
      enableSorting: false,
      cell: ({ row }) => {
        const d = row.original;
        const items = [
          { label: "View steps", onClick: () => setDetailId(d.id) },
          ...(d.active
            ? []
            : [
                { label: "Edit steps", onClick: () => setEditStepsId(d.id) },
                { label: "Activate this version", onClick: () => setConfirmActivate(d) },
              ]),
        ];
        return (
          <div className="flex justify-end">
            <RowMenu items={items} title={`Actions for ${d.name}`} />
          </div>
        );
      },
    },
  ], []);

  if (!canConfigure) {
    return <Forbidden message="You do not have access to workflow configuration. Requires workflow:configure." />;
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <CardTitle>Workflow definitions ({defs.length})</CardTitle>
          <Button onClick={() => {
            createMut.reset();
            setOpenCreate(true);
          }}>+ New version</Button>
        </CardHeader>
        <CardContent className="space-y-3">
          <FilterBar className="items-center [&_input]:bg-card [&_select]:bg-card">
            <Select className="w-full sm:w-64" aria-label="Filter by document type" value={docType} onChange={(e) => setDocType(e.target.value)}>
              <option value="All">Document type: All</option>
              {docTypesQ.isLoading
                ? <option disabled>Loading...</option>
                : (docTypesQ.data ?? []).map((t) => <option key={t.code} value={t.code}>{t.name}</option>)}
            </Select>
          </FilterBar>
          <p className="text-xs text-muted-foreground">
            An active version is read-only. Changes are a new version: create → edit steps → activate.
            Running approvals stay pinned to the version they started on.
          </p>

          {defsQ.isLoading ? (
            <div className="text-sm text-muted-foreground">Loading...</div>
          ) : defsQ.isError ? (
            <div className="text-sm text-destructive">Failed to load definitions: {getApiErrorMessage(defsQ.error, "")}</div>
          ) : (
              <DataTable columns={columns} data={defs} emptyMessage="No definitions yet · create the first version." />
          )}
        </CardContent>
      </Card>

      {/* Wizard step 1: pick document type + name */}
      <Dialog open={openCreate} onOpenChange={setOpenCreate}>
        <DialogContent>
          <DialogHeader><DialogTitle>New workflow version</DialogTitle></DialogHeader>
          <form onSubmit={handleSubmit((d) => createMut.mutate(d))} className="space-y-3">
            <div>
              <Label>Document type</Label>
              <Select {...register("documentTypeCode")}>
                <option value="">Select...</option>
                {(docTypesQ.data ?? []).map((t) => <option key={t.code} value={t.code}>{t.name}</option>)}
              </Select>
              {errors.documentTypeCode && <p className="text-xs text-destructive">{errors.documentTypeCode.message}</p>}
            </div>
            <div>
              <Label>Version name</Label>
              <Input {...register("name")} placeholder="e.g. Contract Approval v2" />
              {errors.name && <p className="text-xs text-destructive">{errors.name.message}</p>}
            </div>
            <p className="text-xs text-muted-foreground">Next: add approval steps to this draft, then activate it.</p>
            {createMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(createMut.error, "Create failed")}</div>}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpenCreate(false)}>Cancel</Button>
              <Button type="submit" disabled={createMut.isPending}>{createMut.isPending ? "Creating..." : "Create & edit steps"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Wizard step 2 / Edit: replace steps of a draft definition */}
      {editDef && (
        <StepEditorDialog
          definition={editDef}
          roleCodes={(rolesQ.data ?? []).map((r) => r.code)}
          rolesLoading={rolesQ.isLoading}
          onClose={() => setEditStepsId(null)}
          onSaved={() => { invalidate(); setEditStepsId(null); }}
        />
      )}

      {/* Read-only step viewer */}
      <Dialog open={!!detailId} onOpenChange={(o) => !o && setDetailId(null)}>
        <DialogContent className="max-w-lg">
          <DialogHeader><DialogTitle>{detailDef?.name} · steps</DialogTitle></DialogHeader>
          {detailDef && (
            <ol className="space-y-2">
              {detailDef.steps.length === 0 && <p className="text-sm text-muted-foreground">No steps defined yet.</p>}
              {detailDef.steps.map((s, i) => (
                <li key={s.id} className="flex items-center gap-3 rounded-lg border border-border p-3 text-sm">
                  <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-white">{i + 1}</span>
                  <div className="min-w-0">
                    <div className="font-medium">{s.name}</div>
                    <div className="text-xs text-muted-foreground">{roleLabel(s.approverRole)} · SLA {s.slaHours}h</div>
                  </div>
                </li>
              ))}
            </ol>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setDetailId(null)}>Close</Button>
            {detailDef && !detailDef.active && (
              <Button onClick={() => { setDetailId(null); setEditStepsId(detailDef.id); }}>Edit steps</Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!confirmActivate}
        title={`Activate ${confirmActivate?.name}?`}
        body={(
          <div className="space-y-2">
            <p>This deactivates the current active version for <span className="font-medium">{confirmActivate?.documentTypeName}</span> and activates <span className="font-medium">v{confirmActivate?.versionNo}</span> in one transaction.</p>
            <p className="text-muted-foreground">New submissions use the new version. Approvals already running stay on their original version.</p>
          </div>
        )}
        confirmLabel="Activate version"
        pendingLabel="Activating..."
        pending={activateMut.isPending}
        error={activateMut.isError ? activateMut.error : undefined}
        onConfirm={() => confirmActivate && activateMut.mutate(confirmActivate.id)}
        onCancel={() => setConfirmActivate(null)}
      />
    </div>
  );
}

function StepEditorDialog({ definition, roleCodes, rolesLoading, onClose, onSaved }: {
  definition: WorkflowDefinitionResponse;
  roleCodes: string[];
  rolesLoading: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const defaultRole = roleCodes[0] ?? "";
  const [steps, setSteps] = useState<StepRequest[]>(
    definition.steps.length > 0
      ? definition.steps.map((s) => ({ name: s.name, approverRole: s.approverRole, slaHours: s.slaHours }))
      : [emptyStep(defaultRole)],
  );
  const [fieldErrors, setFieldErrors] = useState<string | null>(null);

  const saveMut = useMutation({
    mutationFn: () => workflowApi.updateSteps(definition.id, steps),
    onSuccess: onSaved,
  });

  function setStep(i: number, patch: Partial<StepRequest>) {
    setSteps((prev) => prev.map((s, j) => (j === i ? { ...s, ...patch } : s)));
  }
  function move(i: number, dir: -1 | 1) {
    setSteps((prev) => {
      const next = [...prev];
      const j = i + dir;
      if (j < 0 || j >= next.length) return prev;
      [next[i], next[j]] = [next[j], next[i]];
      return next;
    });
  }

  function validate(): boolean {
    if (steps.length === 0) { setFieldErrors("Add at least one step."); return false; }
    for (const [i, s] of steps.entries()) {
      const parsed = stepSchema.safeParse(s);
      if (!parsed.success) {
        setFieldErrors(`Step ${i + 1}: name, approver role and SLA (min 1h) are required.`);
        return false;
      }
    }
    setFieldErrors(null);
    return true;
  }

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl overflow-hidden p-0">
        <DialogHeader className="shrink-0 px-6 pt-6">
          <DialogTitle>Edit steps · {definition.name} (v{definition.versionNo}, draft)</DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto px-6 pb-4">
          {steps.map((s, i) => (
            <div key={i} className="rounded-lg border border-border p-3">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-xs font-semibold text-muted-foreground">STEP {i + 1}</span>
                <div className="flex gap-1">
                  <Button type="button" variant="outline" className="h-7 px-2 text-xs" disabled={i === 0} onClick={() => move(i, -1)}>↑</Button>
                  <Button type="button" variant="outline" className="h-7 px-2 text-xs" disabled={i === steps.length - 1} onClick={() => move(i, 1)}>↓</Button>
                  <Button type="button" variant="outline" className="h-7 px-2 text-xs text-destructive" disabled={steps.length === 1} onClick={() => setSteps((prev) => prev.filter((_, j) => j !== i))}>Remove</Button>
                </div>
              </div>
              <div className="grid gap-2 sm:grid-cols-[1fr_180px_110px]">
                <div><Label>Step name</Label><Input value={s.name} onChange={(e) => setStep(i, { name: e.target.value })} placeholder="e.g. Legal review" /></div>
                <div>
                  <Label>Approver role</Label>
                  <Select value={s.approverRole} onChange={(e) => setStep(i, { approverRole: e.target.value })}>
                    {rolesLoading && <option disabled>Loading...</option>}
                    {s.approverRole && !roleCodes.includes(s.approverRole) && <option value={s.approverRole}>{roleLabel(s.approverRole)}</option>}
                    {roleCodes.map((code) => <option key={code} value={code}>{roleLabel(code)}</option>)}
                  </Select>
                </div>
                <div><Label>SLA (hours)</Label><Input type="number" min={1} value={s.slaHours} onChange={(e) => setStep(i, { slaHours: Number(e.target.value) })} /></div>
              </div>
            </div>
          ))}
          <Button type="button" variant="outline" onClick={() => setSteps((prev) => [...prev, emptyStep(defaultRole || (prev[prev.length - 1]?.approverRole ?? ""))])}>+ Add step</Button>
          {fieldErrors && <div className="text-sm text-destructive">{fieldErrors}</div>}
          {saveMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(saveMut.error, "Save failed · the definition may have been activated meanwhile")}</div>}
        </div>
        <DialogFooter className="mt-0 shrink-0 border-t bg-card px-6 py-4">
          <Button type="button" variant="outline" onClick={onClose}>Cancel</Button>
          <Button
            type="button"
            disabled={saveMut.isPending}
            onClick={() => { if (validate()) saveMut.mutate(); }}
          >
            {saveMut.isPending ? "Saving..." : "Save steps"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
