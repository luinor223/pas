import { useId } from "react";
import { useMutation } from "@tanstack/react-query";
import { useFieldArray, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import type { AddendumResponse } from "../types/contractTypes";
import { contractApi } from "../services/contractApi";
import { ADDENDUM_CHANGE_TYPES } from "../contractOptions";
import { Button } from "@/shared/components/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
import { Select } from "@/shared/components/select";
import { Textarea } from "@/shared/components/textarea";
import { getApiErrorMessage } from "@/shared/api/errors";
import { humanize } from "@/shared/lib/text";

const serviceSchema = z.object({
  serviceItemId: z.string().optional().nullable(),
  serviceCode: z.string().min(1, "Service code is required"),
  serviceName: z.string().min(1, "Service name is required"),
  unit: z.string().optional().nullable(),
  scopeNote: z.string().optional().nullable(),
});

const editSchema = z.object({
  changeType: z.string().min(1),
  description: z.string().optional().nullable(),
  effectiveFrom: z.string().min(1, "Effective date is required"),
  newValidTo: z.string().optional().nullable(),
  paymentTermOverride: z.string().optional().nullable(),
  services: z.array(serviceSchema),
}).superRefine((data, context) => {
  if (data.changeType === "TERM_EXTENSION" && !data.newValidTo) context.addIssue({ code: "custom", path: ["newValidTo"], message: "New valid-to date is required" });
  if (data.changeType === "PAYMENT_TERMS" && !data.paymentTermOverride?.trim()) context.addIssue({ code: "custom", path: ["paymentTermOverride"], message: "Payment term is required" });
  if (data.changeType === "ADDED_SERVICE" && data.services.length === 0) context.addIssue({ code: "custom", path: ["services"], message: "Add at least one service" });
});

type EditForm = z.infer<typeof editSchema>;

export function AddendumEditDialog({ addendum, onClose, onSaved }: { addendum: AddendumResponse; onClose: () => void; onSaved: (updated: AddendumResponse) => void | Promise<void> }) {
  const fieldId = useId();
  const form = useForm<EditForm>({
    resolver: zodResolver(editSchema),
    defaultValues: {
      changeType: addendum.changeType,
      description: addendum.description ?? "",
      effectiveFrom: addendum.effectiveFrom,
      newValidTo: addendum.newValidTo ?? "",
      paymentTermOverride: addendum.paymentTermOverride ?? "",
      services: addendum.services.map((service) => ({
        serviceItemId: service.serviceItemId,
        serviceCode: service.serviceCode,
        serviceName: service.serviceName,
        unit: service.unit ?? "",
        scopeNote: service.scopeNote ?? "",
      })),
    },
  });
  const services = useFieldArray({ control: form.control, name: "services" });
  const changeType = form.watch("changeType");
  const mutation = useMutation({
    mutationFn: (data: EditForm) => contractApi.updateAddendum(addendum.id, {
      contractId: addendum.contractId,
      changeType: data.changeType,
      description: data.description?.trim() || null,
      effectiveFrom: data.effectiveFrom,
      newValidTo: data.newValidTo || null,
      paymentTermOverride: data.paymentTermOverride?.trim() || null,
      services: data.services.map((service) => ({
        serviceItemId: service.serviceItemId || null,
        serviceCode: service.serviceCode,
        serviceName: service.serviceName,
        unit: service.unit?.trim() || null,
        scopeNote: service.scopeNote?.trim() || null,
      })),
      version: addendum.version,
    }),
    onSuccess: onSaved,
  });

  return (
    <Dialog open onOpenChange={(open) => { if (!open && !mutation.isPending) onClose(); }}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-auto">
        <DialogHeader><DialogTitle>Edit {addendum.addendumNo}</DialogTitle></DialogHeader>
        <form onSubmit={form.handleSubmit((data) => mutation.mutate(data))} className="space-y-3">
          <div><div className="text-xs text-muted-foreground">PARENT CONTRACT</div><div className="text-sm">{addendum.contractNo}</div></div>
          <div><Label htmlFor={`${fieldId}-type`}>Change type *</Label><Select id={`${fieldId}-type`} {...form.register("changeType")}>{ADDENDUM_CHANGE_TYPES.map((type) => <option key={type} value={type}>{humanize(type)}</option>)}</Select></div>
          <div><Label htmlFor={`${fieldId}-description`}>Description</Label><Textarea id={`${fieldId}-description`} {...form.register("description")} /></div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <div><Label htmlFor={`${fieldId}-effective`}>Effective from *</Label><Input id={`${fieldId}-effective`} type="date" {...form.register("effectiveFrom")} />{form.formState.errors.effectiveFrom && <p className="text-xs text-destructive">{form.formState.errors.effectiveFrom.message}</p>}</div>
            {changeType === "TERM_EXTENSION" && <div><Label htmlFor={`${fieldId}-valid-to`}>New valid to *</Label><Input id={`${fieldId}-valid-to`} type="date" {...form.register("newValidTo")} />{form.formState.errors.newValidTo && <p className="text-xs text-destructive">{form.formState.errors.newValidTo.message}</p>}</div>}
            {changeType === "PAYMENT_TERMS" && <div><Label htmlFor={`${fieldId}-payment`}>Payment term *</Label><Input id={`${fieldId}-payment`} {...form.register("paymentTermOverride")} />{form.formState.errors.paymentTermOverride && <p className="text-xs text-destructive">{form.formState.errors.paymentTermOverride.message}</p>}</div>}
          </div>
          {changeType === "ADDED_SERVICE" && (
            <div className="space-y-2">
              <div className="flex items-center justify-between"><Label>Services</Label><Button type="button" size="sm" variant="outline" onClick={() => services.append({ serviceItemId: null, serviceCode: "", serviceName: "", unit: "", scopeNote: "" })}>+ Service</Button></div>
              {services.fields.map((field, index) => (
                <div key={field.id} className="grid grid-cols-1 items-end gap-2 rounded border p-2 sm:grid-cols-5">
                  <Input aria-label={`Service ${index + 1} code`} {...form.register(`services.${index}.serviceCode`)} placeholder="Code" />
                  <Input aria-label={`Service ${index + 1} name`} {...form.register(`services.${index}.serviceName`)} placeholder="Name" />
                  <Input aria-label={`Service ${index + 1} unit`} {...form.register(`services.${index}.unit`)} placeholder="Unit" />
                  <Input aria-label={`Service ${index + 1} scope`} {...form.register(`services.${index}.scopeNote`)} placeholder="Scope" />
                  <Button type="button" size="sm" variant="ghost" onClick={() => services.remove(index)}>Remove</Button>
                </div>
              ))}
              {form.formState.errors.services?.root?.message && <p className="text-xs text-destructive">{form.formState.errors.services.root.message}</p>}
            </div>
          )}
          {mutation.isError && <div role="alert" className="text-sm text-destructive">{getApiErrorMessage(mutation.error, "Failed to update addendum")}</div>}
          <DialogFooter><Button type="button" variant="outline" disabled={mutation.isPending} onClick={onClose}>Cancel</Button><Button type="submit" disabled={mutation.isPending}>{mutation.isPending ? "Saving..." : "Save changes"}</Button></DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
