// Contract domain DTOs mirroring services/contract controllers.

export type { PageResponse } from "@/shared/api/client";

export type CustomerContactResponse = {
  id: string;
  fullName: string;
  title: string | null;
  email: string | null;
  phone: string | null;
  primary: boolean;
};

export type CustomerResponse = {
  id: string;
  code: string;
  name: string;
  shortName: string | null;
  taxCode: string | null;
  address: string | null;
  representativeName: string | null;
  representativePosition: string | null;
  segment: string | null;
  status: "ACTIVE" | "SUSPENDED";
  contacts: CustomerContactResponse[];
  primaryContact: CustomerContactResponse | null;
  contractsCount: number;
  createdAt: string;
  createdByName: string | null;
  updatedAt: string;
};

export type CustomerContactRequest = {
  fullName: string;
  title?: string | null;
  email?: string | null;
  phone?: string | null;
  primary: boolean;
};

export type CustomerRequest = {
  name: string;
  shortName?: string | null;
  taxCode?: string | null;
  address?: string | null;
  representativeName?: string | null;
  representativePosition?: string | null;
  segment?: string | null;
  contacts: CustomerContactRequest[];
};

export type ContractResponse = {
  id: string;
  contractNo: string;
  customerId: string;
  customerName: string;
  description: string | null;
  serviceGroup: string;
  value: number | null;
  currency: string;
  validFrom: string;
  validTo: string;
  paymentTerm: string | null;
  billingCycle: string;
  vatRate: number | null;
  penaltyTerms: string | null;
  serviceClause: string | null;
  status: string;
  editable: boolean;
  version: number;
  createdAt: string;
  createdByName: string | null;
  updatedAt: string;
};

export type ContractRequest = {
  customerId: string;
  description?: string | null;
  serviceGroup: string;
  value?: number | null;
  currency?: string | null;
  validFrom: string;
  validTo: string;
  paymentTerm?: string | null;
  billingCycle?: string | null;
  vatRate?: number | null;
  penaltyTerms?: string | null;
  serviceClause?: string | null;
  version?: number | null;
};

export type AddendumServiceLine = {
  id: string;
  serviceItemId: string | null;
  serviceCode: string;
  serviceName: string;
  unit: string | null;
  scopeNote: string | null;
};

export type AddendumResponse = {
  id: string;
  addendumNo: string;
  contractId: string;
  contractNo: string;
  changeType: string;
  description: string | null;
  effectiveFrom: string;
  newValidTo: string | null;
  paymentTermOverride: string | null;
  status: string;
  services: AddendumServiceLine[];
  version: number;
};

export type AddendumRequest = {
  contractId: string;
  changeType: string;
  description?: string | null;
  effectiveFrom: string;
  newValidTo?: string | null;
  paymentTermOverride?: string | null;
  services?: { serviceItemId?: string | null; serviceCode: string; serviceName: string; unit?: string | null; scopeNote?: string | null }[];
  version?: number | null;
};

export type AttachmentResponse = {
  id: string;
  ownerType: "CONTRACT" | "ADDENDUM";
  ownerId: string;
  fileName: string;
  contentType: string | null;
  sizeBytes: number;
  uploadedAt: string;
};

export type StatusHistoryResponse = {
  id: string;
  fromStatus: string | null;
  toStatus: string;
  trigger: string;
  triggerRef: string | null;
  actorId: string | null;
  actorName: string | null;
  note: string | null;
  occurredAt: string;
};

export type ProgressResponse = {
  documentStatus: string;
  workflowState: string;
  instanceId: string | null;
  definitionVersionNo: number | null;
  requestedByName: string | null;
  startedAt: string | null;
  priority: string | null;
  currentStep: {
    stepNo: number;
    name: string | null;
    approverRole: string | null;
    status: string | null;
    assigneeNames: string[];
    action: { actorName: string | null; actionedAt: string | null; comment: string | null; action: string | null } | null;
    activatedAt: string | null;
    slaHours: number | null;
    overdue: boolean;
  } | null;
  steps: {
    stepNo: number;
    name: string | null;
    approverRole: string | null;
    status: string | null;
    assigneeNames: string[];
    action: { actorName: string | null; actionedAt: string | null; comment: string | null; action: string | null } | null;
    activatedAt: string | null;
    slaHours: number | null;
    overdue: boolean;
  }[];
};

export type SubmitResponse = { status: string; workflowState: string };
export type CancelResponse = { status: string; outcome: string };
