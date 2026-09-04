import { useId } from "react";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/shared/components/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/shared/components/dialog";
import { Input } from "@/shared/components/input";
import { Label } from "@/shared/components/label";
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

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-auto">
        <DialogHeader><DialogTitle>Edit contract {contract.contractNo}</DialogTitle></DialogHeader>
        <form onSubmit={handleSubmit((data) => updateMut.mutate(data))} className="space-y-3">
          <div>
            <CustomerPicker
              value={watch("customerId")}
              onChange={(id) => setValue("customerId", id, { shouldValidate: true })}
              label="Customer *"
              placeholder="Type code or name..."
              status="ACTIVE"
            />
            {errors.customerId && <p className="text-xs text-destructive">{errors.customerId.message}</p>}
          </div>
          <div><Label htmlFor={`${formId}-description`}>Description</Label><Textarea id={`${formId}-description`} {...register("description")} /></div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <div><Label htmlFor={`${formId}-service-group`}>Service group *</Label><Select id={`${formId}-service-group`} {...register("serviceGroup")}>{SERVICE_GROUPS.map((group) => <option key={group} value={group}>{group}</option>)}</Select></div>
            <div><Label htmlFor={`${formId}-currency`}>Currency</Label><Input id={`${formId}-currency`} {...register("currency")} /></div>
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <div><Label htmlFor={`${formId}-value`}>Value</Label><Input id={`${formId}-value`} type="number" step="0.01" {...register("value")} />{errors.value && <p className="text-xs text-destructive">{errors.value.message}</p>}</div>
            <div><Label htmlFor={`${formId}-valid-from`}>Valid from *</Label><Input id={`${formId}-valid-from`} type="date" {...register("validFrom")} />{errors.validFrom && <p className="text-xs text-destructive">{errors.validFrom.message}</p>}</div>
            <div><Label htmlFor={`${formId}-valid-to`}>Valid to *</Label><Input id={`${formId}-valid-to`} type="date" {...register("validTo")} />{errors.validTo && <p className="text-xs text-destructive">{errors.validTo.message}</p>}</div>
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <div><Label htmlFor={`${formId}-payment-term`}>Payment term</Label><Input id={`${formId}-payment-term`} {...register("paymentTerm")} /></div>
            <div><Label htmlFor={`${formId}-billing-cycle`}>Billing cycle</Label><Input id={`${formId}-billing-cycle`} {...register("billingCycle")} readOnly /></div>
            <div><Label htmlFor={`${formId}-vat-rate`}>VAT rate</Label><Input id={`${formId}-vat-rate`} type="number" step="0.01" {...register("vatRate")} />{errors.vatRate && <p className="text-xs text-destructive">{errors.vatRate.message}</p>}</div>
          </div>
          <div><Label htmlFor={`${formId}-penalty-terms`}>Penalty terms</Label><Textarea id={`${formId}-penalty-terms`} {...register("penaltyTerms")} /></div>
          <div><Label htmlFor={`${formId}-service-clause`}>Service clause</Label><Textarea id={`${formId}-service-clause`} {...register("serviceClause")} /></div>
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
