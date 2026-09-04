import type { components } from "@/generated/contract-api";

export type { PageResponse } from "@/shared/api/paging";

type Schemas = components["schemas"];
// Jackson always emits record response fields, while Springdoc leaves them optional; requests
// retain their documented optionality and accept the explicit nulls already supported by the API.
type RequiredResponse<Value> = { [Key in keyof Value]-?: Exclude<Value[Key], undefined> };
type NullableOptionals<Value> = {
  [Key in keyof Value]: undefined extends Value[Key] ? Value[Key] | null : Value[Key];
};
type WireValue<Value> = Exclude<Value, null | undefined> extends readonly (infer Item)[]
  ? WireValue<Item>[]
  : Exclude<Value, null | undefined> extends object
    ? { [Key in keyof Exclude<Value, null | undefined>]-?: WireValue<Exclude<Value, null | undefined>[Key]> }
    : Exclude<Value, null | undefined>;
type IncompatibleKeys<Base, Fields> = {
  [Key in keyof Fields]-?: Key extends keyof Base
    ? WireValue<Fields[Key]> extends WireValue<Base[Key]>
      ? never
      : Key
    : Key;
}[keyof Fields];
// Refine nulls and enums, but reject missing fields or values incompatible with generated OpenAPI.
type Override<Base, Fields> = [IncompatibleKeys<Base, Fields>] extends [never]
  ? Omit<Base, keyof Fields> & Fields
  : never;
type AssertNever<Value extends never> = Value;

/** Compilation proves an incompatible handwritten refinement cannot mask generated schema drift. */
export type ContractApiOverrideSafetyCheck = AssertNever<
  Override<{ value?: number | null }, { value: string | null }>
>;

export type CustomerContactResponse = Override<
  RequiredResponse<Schemas["CustomerContactResponse"]>,
  { title: string | null; email: string | null; phone: string | null }
>;

export type CustomerResponse = Override<
  RequiredResponse<Schemas["CustomerResponse"]>,
  {
    shortName: string | null;
    taxCode: string | null;
    address: string | null;
    representativeName: string | null;
    representativePosition: string | null;
    segment: string | null;
    status: "ACTIVE" | "SUSPENDED";
    contacts: CustomerContactResponse[];
    primaryContact: CustomerContactResponse | null;
    contractsCount: number | null;
    createdByName: string | null;
  }
>;

export type CustomerMetricsResponse = Override<
  RequiredResponse<Schemas["CustomerMetricsResponse"]>,
  { approvedContractValues: Array<RequiredResponse<Schemas["CurrencyValue"]>> }
>;

export type CustomerContactRequest = Override<
  NullableOptionals<Schemas["CustomerContactRequest"]>,
  { primary: boolean }
>;

export type CustomerRequest = Override<
  NullableOptionals<Schemas["CustomerRequest"]>,
  { contacts: CustomerContactRequest[] }
>;

export type ContractResponse = Override<
  RequiredResponse<Schemas["ContractResponse"]>,
  {
    description: string | null;
    value: number | null;
    paymentTerm: string | null;
    vatRate: number | null;
    penaltyTerms: string | null;
    serviceClause: string | null;
    createdByName: string | null;
  }
>;

export type ContractRequest = NullableOptionals<Schemas["ContractRequest"]>;

export type AddendumServiceLine = Override<
  RequiredResponse<Schemas["AddendumResponseServiceLine"]>,
  { serviceItemId: string | null; unit: string | null; scopeNote: string | null }
>;

type AddendumRequestServiceLine = NullableOptionals<Schemas["AddendumRequestServiceLine"]>;
type AddendumRequestFields = { services?: AddendumRequestServiceLine[] | null };

export type AddendumResponse = Override<
  RequiredResponse<Schemas["AddendumResponse"]>,
  {
    description: string | null;
    newValidTo: string | null;
    paymentTermOverride: string | null;
    services: AddendumServiceLine[];
  }
>;

export type AddendumRequest = Override<
  NullableOptionals<Schemas["AddendumRequest"]>,
  AddendumRequestFields
>;

export type AttachmentResponse = Override<
  RequiredResponse<Schemas["AttachmentResponse"]>,
  { ownerType: "CONTRACT" | "ADDENDUM"; contentType: string | null }
>;

export type StatusHistoryResponse = Override<
  RequiredResponse<Schemas["StatusHistoryResponse"]>,
  {
    fromStatus: string | null;
    triggerRef: string | null;
    actorId: string | null;
    actorName: string | null;
    note: string | null;
  }
>;

type ProgressAction = Override<
  RequiredResponse<Schemas["Action"]>,
  {
    actorName: string | null;
    actionedAt: string | null;
    comment: string | null;
    action: string | null;
  }
>;

type ProgressStep = Override<
  RequiredResponse<Schemas["Step"]>,
  {
    name: string | null;
    approverRole: string | null;
    status: string | null;
    action: ProgressAction | null;
    activatedAt: string | null;
  }
>;

export type ProgressResponse = Override<
  RequiredResponse<Schemas["ProgressResponse"]>,
  {
    instanceId: string | null;
    definitionVersionNo: number | null;
    requestedByName: string | null;
    startedAt: string | null;
    priority: string | null;
    currentStep: ProgressStep | null;
    steps: ProgressStep[];
  }
>;

export type SubmitResponse = RequiredResponse<Schemas["SubmitResponse"]>;
export type CancelResponse = Override<
  RequiredResponse<Schemas["CancelResponse"]>,
  { status: "CANCELLED" | "PENDING"; detail: string | null }
>;
export type SigningRequestStateResponse = Override<
  RequiredResponse<Schemas["SigningRequestStateResponse"]>,
  { sessionId: string | null }
>;

// Signing sessions are owned by e-sign and remain outside the contract-service OpenAPI document.
export type SigningSessionResponse = {
  id: string;
  sessionNo: string;
  documentTypeCode: "CONTRACT" | "ADDENDUM" | "PAYMENT_STATEMENT";
  documentId: string;
  documentNo: string;
  customerName: string;
  signerName: string;
  signerEmail: string;
  provider: string | null;
  providerRef: string | null;
  status: "PENDING_SEND" | "SIGNING" | "SIGNED" | "FAILED" | "CANCELLED";
  attempts: number;
  lastError: string | null;
  requestedByName: string | null;
  sentAt: string | null;
  completedAt: string | null;
  createdAt: string;
};
