import { useId } from "react";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/shared/components/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Input } from "@/shared/components/input";
import { Select } from "@/shared/components/select";
import { Textarea } from "@/shared/components/textarea";
import { getApiErrorMessage } from "@/shared/api/errors";
import { contractApi } from "../services/contractApi";
import type { ContractResponse } from "../types/contractTypes";
import { SERVICE_GROUPS } from "../contractOptions";
import {
  contractFormSchema,
  contractFormValues,
  contractRequest,
  type ContractFormData,
} from "../contractForm";
import { CustomerPicker } from "./CustomerPicker";
import { EmptyFieldHint, RequirementLabel, RequirementLegend } from "./FormRequirement";

export function ContractEditDialog({ contract, onClose, onSaved }: {
  contract: ContractResponse;
  onClose: () => void;
  onSaved: (updated: ContractResponse) => void | Promise<void>;
}) {
  const formId = useId();
  const { register, handleSubmit, watch, setValue, formState: { errors } } = useForm<ContractFormData>({
    resolver: zodResolver(contractFormSchema),
    defaultValues: contractFormValues(contract),
  });
  const updateMut = useMutation({
    mutationFn: (data: ContractFormData) => contractApi.updateContract(
      contract.id,
      contractRequest(data, contract.version),
    ),
    onSuccess: onSaved,
  });
  const values = watch();

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-auto">
        <DialogHeader><DialogTitle>Edit contract {contract.contractNo}</DialogTitle></DialogHeader>
        <form onSubmit={handleSubmit((data) => updateMut.mutate(data))} className="space-y-3">
          <RequirementLegend attachmentNote />
          <div>
            <CustomerPicker
              value={values.customerId}
              onChange={(id) => setValue("customerId", id, { shouldValidate: true })}
              label="Customer"
              requirement="draft"
              emptyHint="Select the active customer this contract belongs to."
              placeholder="Type code or name..."
              status="ACTIVE"
            />
            {errors.customerId && <p className="text-xs text-destructive">{errors.customerId.message}</p>}
          </div>
          <div><RequirementLabel htmlFor={`${formId}-description`}>Description</RequirementLabel><Textarea id={`${formId}-description`} {...register("description")} /><EmptyFieldHint show={!values.description?.trim()}>Optional: briefly describe the commercial purpose of this contract.</EmptyFieldHint></div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <div><RequirementLabel htmlFor={`${formId}-service-group`} kind="draft">Service group</RequirementLabel><Select id={`${formId}-service-group`} aria-required="true" {...register("serviceGroup")}>{SERVICE_GROUPS.map((group) => <option key={group} value={group}>{group}</option>)}</Select><EmptyFieldHint show={!values.serviceGroup}>Choose the services covered by this contract.</EmptyFieldHint></div>
            <div><RequirementLabel htmlFor={`${formId}-currency`}>Currency</RequirementLabel><Input id={`${formId}-currency`} {...register("currency")} /><EmptyFieldHint show={!values.currency?.trim()}>Enter the three-letter billing currency, for example VND.</EmptyFieldHint></div>
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <div><RequirementLabel htmlFor={`${formId}-value`}>Value</RequirementLabel><Input id={`${formId}-value`} type="number" step="0.01" {...register("value")} />{errors.value ? <p className="text-xs text-destructive">{errors.value.message}</p> : <EmptyFieldHint show={!values.value}>Optional in a draft; enter the agreed value when known.</EmptyFieldHint>}</div>
            <div><RequirementLabel htmlFor={`${formId}-valid-from`} kind="draft">Valid from</RequirementLabel><Input id={`${formId}-valid-from`} type="date" aria-required="true" {...register("validFrom")} />{errors.validFrom ? <p className="text-xs text-destructive">{errors.validFrom.message}</p> : <EmptyFieldHint show={!values.validFrom}>Set the date this contract begins.</EmptyFieldHint>}</div>
            <div><RequirementLabel htmlFor={`${formId}-valid-to`} kind="draft">Valid to</RequirementLabel><Input id={`${formId}-valid-to`} type="date" aria-required="true" {...register("validTo")} />{errors.validTo ? <p className="text-xs text-destructive">{errors.validTo.message}</p> : <EmptyFieldHint show={!values.validTo}>Set the date this contract ends.</EmptyFieldHint>}</div>
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <div><RequirementLabel htmlFor={`${formId}-payment-term`} kind="submit">Payment term</RequirementLabel><Input id={`${formId}-payment-term`} {...register("paymentTerm")} /><EmptyFieldHint show={!values.paymentTerm?.trim()} kind="submit">Required before submission. Example: NET30.</EmptyFieldHint></div>
            <div><RequirementLabel htmlFor={`${formId}-billing-cycle`}>Billing cycle</RequirementLabel><Input id={`${formId}-billing-cycle`} {...register("billingCycle")} readOnly /></div>
            <div><RequirementLabel htmlFor={`${formId}-vat-rate`} kind="submit">VAT rate</RequirementLabel><Input id={`${formId}-vat-rate`} type="number" step="0.01" {...register("vatRate")} />{errors.vatRate ? <p className="text-xs text-destructive">{errors.vatRate.message}</p> : <EmptyFieldHint show={!values.vatRate} kind="submit">Required before submission. Enter 0 when no VAT applies.</EmptyFieldHint>}</div>
          </div>
          <div><RequirementLabel htmlFor={`${formId}-penalty-terms`}>Penalty terms</RequirementLabel><Textarea id={`${formId}-penalty-terms`} {...register("penaltyTerms")} /><EmptyFieldHint show={!values.penaltyTerms?.trim()}>Optional: describe late-performance or service penalties.</EmptyFieldHint></div>
          <div><RequirementLabel htmlFor={`${formId}-service-clause`}>Service clause</RequirementLabel><Textarea id={`${formId}-service-clause`} {...register("serviceClause")} /><EmptyFieldHint show={!values.serviceClause?.trim()}>Optional: describe the included services and commercial scope.</EmptyFieldHint></div>
          {updateMut.isError && <div role="alert" className="text-sm text-destructive">{getApiErrorMessage(updateMut.error, "Update failed")}</div>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>Cancel</Button>
            <Button type="submit" disabled={updateMut.isPending}>{updateMut.isPending ? "Saving..." : "Save changes"}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
