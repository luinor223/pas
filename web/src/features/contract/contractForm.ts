import { z } from "zod";
import type { ContractRequest, ContractResponse } from "./types/contractTypes";

export const contractFormSchema = z.object({
  customerId: z.string().min(1, "Customer is required"),
  description: z.string().optional().nullable(),
  serviceGroup: z.string().min(1, "Service group is required"),
  value: z.string().optional().nullable(),
  currency: z.string().optional().nullable(),
  validFrom: z.string().min(1, "Valid-from date is required"),
  validTo: z.string().min(1, "Valid-to date is required"),
  paymentTerm: z.string().optional().nullable(),
  billingCycle: z.string().optional().nullable(),
  vatRate: z.string().optional().nullable(),
  penaltyTerms: z.string().optional().nullable(),
  serviceClause: z.string().optional().nullable(),
}).refine((data) => !data.validFrom || !data.validTo || data.validFrom <= data.validTo, {
  message: "Valid-from date must not be after the valid-to date",
  path: ["validTo"],
}).refine((data) => data.value == null || data.value === "" || !Number.isNaN(Number(data.value)), {
  message: "Value must be a number",
  path: ["value"],
}).refine((data) => data.vatRate == null || data.vatRate === ""
  || (!Number.isNaN(Number(data.vatRate)) && Number(data.vatRate) >= 0 && Number(data.vatRate) <= 100), {
  message: "VAT rate must be between 0 and 100",
  path: ["vatRate"],
});

export type ContractFormData = z.infer<typeof contractFormSchema>;

export function contractFormValues(contract: ContractResponse): ContractFormData {
  return {
    customerId: contract.customerId,
    description: contract.description ?? "",
    serviceGroup: contract.serviceGroup,
    value: contract.value == null ? "" : String(contract.value),
    currency: contract.currency,
    validFrom: contract.validFrom,
    validTo: contract.validTo,
    paymentTerm: contract.paymentTerm ?? "",
    billingCycle: contract.billingCycle,
    vatRate: contract.vatRate == null ? "" : String(contract.vatRate),
    penaltyTerms: contract.penaltyTerms ?? "",
    serviceClause: contract.serviceClause ?? "",
  };
}

export function contractRequest(data: ContractFormData, version?: number): ContractRequest {
  const numberOrNull = (value: string | null | undefined) => value == null || value === ""
    ? null
    : Number(value);
  return {
    customerId: data.customerId,
    description: data.description || null,
    serviceGroup: data.serviceGroup,
    value: numberOrNull(data.value),
    currency: data.currency || "VND",
    validFrom: data.validFrom,
    validTo: data.validTo,
    paymentTerm: data.paymentTerm || null,
    billingCycle: data.billingCycle || "MONTHLY",
    vatRate: numberOrNull(data.vatRate),
    penaltyTerms: data.penaltyTerms || null,
    serviceClause: data.serviceClause || null,
    ...(version === undefined ? {} : { version }),
  };
}
