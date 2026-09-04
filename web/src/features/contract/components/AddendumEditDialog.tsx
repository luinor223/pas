import { useId } from "react";
import { useMutation } from "@tanstack/react-query";
import { useFieldArray, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import type { AddendumResponse } from "../types/contractTypes";
import { contractApi } from "../services/contractApi";
import { ADDENDUM_CHANGE_TYPES, addendumChangeTypeLabel } from "../contractOptions";
import { Button } from "@/shared/components/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Input } from "@/shared/components/input";
import { Select } from "@/shared/components/select";
import { Textarea } from "@/shared/components/textarea";
import { getApiErrorMessage } from "@/shared/api/errors";
import { EmptyFieldHint, RequirementLabel, RequirementLegend } from "./FormRequirement";

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
  const values = form.watch();
  const changeType = values.changeType;
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
          <RequirementLegend attachmentNote />
          <div><div className="text-xs text-muted-foreground">PARENT CONTRACT</div><div className="text-sm">{addendum.contractNo}</div></div>
          <div><RequirementLabel htmlFor={`${fieldId}-type`} kind="draft">Change type</RequirementLabel><Select id={`${fieldId}-type`} aria-required="true" {...form.register("changeType")}>{ADDENDUM_CHANGE_TYPES.map((type) => <option key={type} value={type}>{addendumChangeTypeLabel(type)}</option>)}</Select><EmptyFieldHint show={!values.changeType}>Choose what this addendum changes.</EmptyFieldHint></div>
          <div><RequirementLabel htmlFor={`${fieldId}-description`}>Description</RequirementLabel><Textarea id={`${fieldId}-description`} {...form.register("description")} /><EmptyFieldHint show={!values.description?.trim()}>Optional: summarize why this change is needed.</EmptyFieldHint></div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <div><RequirementLabel htmlFor={`${fieldId}-effective`} kind="draft">Effective from</RequirementLabel><Input id={`${fieldId}-effective`} type="date" aria-required="true" {...form.register("effectiveFrom")} />{form.formState.errors.effectiveFrom ? <p className="text-xs text-destructive">{form.formState.errors.effectiveFrom.message}</p> : <EmptyFieldHint show={!values.effectiveFrom}>Set the date this change takes effect.</EmptyFieldHint>}</div>
            {changeType === "TERM_EXTENSION" && <div><RequirementLabel htmlFor={`${fieldId}-valid-to`} kind="draft">New valid to</RequirementLabel><Input id={`${fieldId}-valid-to`} type="date" aria-required="true" {...form.register("newValidTo")} />{form.formState.errors.newValidTo ? <p className="text-xs text-destructive">{form.formState.errors.newValidTo.message}</p> : <EmptyFieldHint show={!values.newValidTo}>Enter a date later than the contract's current end date.</EmptyFieldHint>}</div>}
            {changeType === "PAYMENT_TERMS" && <div><RequirementLabel htmlFor={`${fieldId}-payment`} kind="draft">Payment term</RequirementLabel><Input id={`${fieldId}-payment`} aria-required="true" {...form.register("paymentTermOverride")} />{form.formState.errors.paymentTermOverride ? <p className="text-xs text-destructive">{form.formState.errors.paymentTermOverride.message}</p> : <EmptyFieldHint show={!values.paymentTermOverride?.trim()}>Enter the replacement payment term, for example NET30.</EmptyFieldHint>}</div>}
          </div>
          {changeType === "ADDED_SERVICE" && (
            <div className="space-y-2">
              <div className="flex items-center justify-between"><RequirementLabel kind="draft">Services</RequirementLabel><Button type="button" size="sm" variant="outline" onClick={() => services.append({ serviceItemId: null, serviceCode: "", serviceName: "", unit: "", scopeNote: "" })}>+ Service</Button></div>
              <EmptyFieldHint show={services.fields.length === 0}>Add at least one service included by this addendum.</EmptyFieldHint>
              {services.fields.map((field, index) => (
                <div key={field.id} className="grid grid-cols-1 items-end gap-2 rounded border p-2 sm:grid-cols-5">
                  <div><RequirementLabel htmlFor={`${fieldId}-service-${index}-code`} kind="draft">Code</RequirementLabel><Input id={`${fieldId}-service-${index}-code`} aria-label={`Service ${index + 1} code`} aria-required="true" {...form.register(`services.${index}.serviceCode`)} /><EmptyFieldHint show={!values.services[index]?.serviceCode?.trim()}>Service identifier.</EmptyFieldHint></div>
                  <div><RequirementLabel htmlFor={`${fieldId}-service-${index}-name`} kind="draft">Name</RequirementLabel><Input id={`${fieldId}-service-${index}-name`} aria-label={`Service ${index + 1} name`} aria-required="true" {...form.register(`services.${index}.serviceName`)} /><EmptyFieldHint show={!values.services[index]?.serviceName?.trim()}>Business name of the service.</EmptyFieldHint></div>
                  <div><RequirementLabel htmlFor={`${fieldId}-service-${index}-unit`}>Unit</RequirementLabel><Input id={`${fieldId}-service-${index}-unit`} aria-label={`Service ${index + 1} unit`} {...form.register(`services.${index}.unit`)} /><EmptyFieldHint show={!values.services[index]?.unit?.trim()}>Optional billing unit.</EmptyFieldHint></div>
                  <div><RequirementLabel htmlFor={`${fieldId}-service-${index}-scope`}>Scope</RequirementLabel><Input id={`${fieldId}-service-${index}-scope`} aria-label={`Service ${index + 1} scope`} {...form.register(`services.${index}.scopeNote`)} /><EmptyFieldHint show={!values.services[index]?.scopeNote?.trim()}>Optional service boundaries.</EmptyFieldHint></div>
                  <Button type="button" size="sm" variant="ghost" aria-label={`Remove service ${index + 1}`} onClick={() => services.remove(index)}>Remove</Button>
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
