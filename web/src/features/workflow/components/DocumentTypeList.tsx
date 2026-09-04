import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ChevronRight } from "lucide-react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { workflowApi } from "../services/workflowApi";
import { documentTypesQuery } from "../hooks/workflowQueries";
import type { DocumentTypeResponse } from "../types/workflowTypes";
import { Button } from "@/shared/components/button";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Badge } from "@/shared/components/badge";
import { DataTable } from "@/shared/components/data-table";
import type { ColumnDef } from "@tanstack/react-table";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/shared/components/dialog";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/components/card";
import { Forbidden } from "@/shared/components/Forbidden";
import { useHasPermission } from "@/features/auth/hooks/usePermissions";
import { getApiErrorMessage } from "@/shared/api/errors";
import { useMemo } from "react";

const editSchema = z.object({
  name: z.string().min(1, "Required"),
  esignEnabled: z.boolean(),
  esignProvider: z.string().nullable(),
}).refine((d) => !d.esignEnabled || (d.esignProvider && d.esignProvider.trim().length > 0), {
  message: "Provider is required when e-sign is enabled",
  path: ["esignProvider"],
});
type FormEdit = z.infer<typeof editSchema>;

export function DocumentTypeList() {
  const qc = useQueryClient();
  const canConfigure = useHasPermission("doctype:configure");
  const [detailCode, setDetailCode] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);

  const typesQ = useQuery(documentTypesQuery);
  const types = useMemo(
    () => [...(typesQ.data ?? [])].sort((a, b) => a.code.localeCompare(b.code)),
    [typesQ.data],
  );
  const detailType = types.find((t) => t.code === detailCode) ?? null;

  const updateMut = useMutation({
    mutationFn: ({ code, data }: { code: string; data: FormEdit }) =>
      workflowApi.updateDocumentType(code, {
        name: data.name,
        esignEnabled: data.esignEnabled,
        esignProvider: data.esignEnabled ? data.esignProvider : null,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["document-types"] });
      setEditing(false);
    },
  });

  const { register, handleSubmit, reset, watch, formState: { errors } } = useForm<FormEdit>({
    resolver: zodResolver(editSchema),
  });
  const esignOn = watch("esignEnabled");

  function openDetail(t: DocumentTypeResponse) {
    reset({ name: t.name, esignEnabled: t.esignEnabled, esignProvider: t.esignProvider });
    updateMut.reset();
    setEditing(false);
    setDetailCode(t.code);
  }

  const columns = useMemo<ColumnDef<DocumentTypeResponse>[]>(() => [
    {
      accessorKey: "code",
      header: "CODE",
      cell: ({ row }) => (
        <div>
          <div className="font-mono font-medium">{row.original.code}</div>
          <div className="text-xs text-muted-foreground">Prefix {row.original.numberPrefix}</div>
        </div>
      ),
    },
    { accessorKey: "name", header: "NAME" },
    {
      id: "esign",
      header: "E-SIGNATURE",
      cell: ({ row }) => row.original.esignEnabled
        ? <Badge className="bg-green-100 text-green-800">Enabled · {row.original.esignProvider}</Badge>
        : <Badge variant="secondary">Disabled</Badge>,
    },
    {
      id: "open",
      header: () => <span className="sr-only">View details</span>,
      enableSorting: false,
      cell: () => <span className="text-muted-foreground"><ChevronRight size={16} aria-hidden="true" /></span>,
    },
  ], []);

  if (!canConfigure) {
    return <Forbidden message="You do not have access to document type configuration. Requires doctype:configure." />;
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader><CardTitle>Document types ({types.length})</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <p className="text-xs text-muted-foreground">
            Code and number prefix are immutable · document numbering depends on them.
            The e-signature card maps here: available after final approval, started manually by the document owner.
          </p>
          {typesQ.isLoading ? (
            <div className="text-sm text-muted-foreground">Loading...</div>
          ) : typesQ.isError ? (
            <div className="text-sm text-destructive">Failed to load document types: {getApiErrorMessage(typesQ.error, "")}</div>
          ) : (
            <DataTable columns={columns} data={types} emptyMessage="No document types" onRowClick={openDetail} />
          )}
        </CardContent>
      </Card>

      <Dialog open={!!detailCode} onOpenChange={(o) => !o && setDetailCode(null)}>
        <DialogContent>
          {detailType && (
            <>
              <DialogHeader>
                <DialogTitle>{detailType.name}</DialogTitle>
                <p className="text-sm text-muted-foreground font-mono">{detailType.code}</p>
              </DialogHeader>

              {!editing ? (
                <div className="space-y-4">
                  <dl className="grid gap-x-8 gap-y-3 rounded-md border bg-muted/20 p-4 text-sm sm:grid-cols-2">
                    <DetailItem label="Code" value={<span className="font-mono">{detailType.code}</span>} />
                    <DetailItem label="Display name" value={detailType.name} />
                    <DetailItem label="Number prefix" value={<span className="font-mono">{detailType.numberPrefix}</span>} />
                    <DetailItem
                      label="E-signature"
                      value={detailType.esignEnabled
                        ? `Enabled · ${detailType.esignProvider}`
                        : "Disabled"}
                    />
                  </dl>
                  <DialogFooter>
                    <Button type="button" variant="outline" onClick={() => setDetailCode(null)}>Close</Button>
                    <Button type="button" onClick={() => setEditing(true)}>Edit</Button>
                  </DialogFooter>
                </div>
              ) : (
                <form onSubmit={handleSubmit((d) => updateMut.mutate({ code: detailType.code, data: d }))} className="space-y-3">
                  <div>
                    <Label>Display name</Label>
                    <Input {...register("name")} />
                    {errors.name && <p className="text-xs text-destructive">{errors.name.message}</p>}
                  </div>
                  <label className="flex cursor-pointer items-start gap-2 rounded-md p-1.5 text-sm hover:bg-muted">
                    <input type="checkbox" className="mt-0.5" {...register("esignEnabled")} />
                    <span>Enable e-signature for this document type</span>
                  </label>
                  <div>
                    <Label>Provider</Label>
                    <Input {...register("esignProvider")} disabled={!esignOn} placeholder="mock" />
                    {errors.esignProvider && <p className="text-xs text-destructive">{errors.esignProvider.message}</p>}
                  </div>
                  {updateMut.isError && <div className="text-sm text-destructive">{getApiErrorMessage(updateMut.error, "Update failed")}</div>}
                  <DialogFooter>
                    <Button type="button" variant="outline" onClick={() => setEditing(false)}>Back</Button>
                    <Button type="submit" disabled={updateMut.isPending}>{updateMut.isPending ? "Saving..." : "Save"}</Button>
                  </DialogFooter>
                </form>
              )}
            </>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function DetailItem({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium">{value}</dd>
    </div>
  );
}
