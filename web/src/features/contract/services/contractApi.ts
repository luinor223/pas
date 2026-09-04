import { api } from "@/shared/api/client";
import { toPage, toParams, type PageMeta } from "@/shared/api/paging";
import type {
  AddendumResponse,
  AttachmentResponse,
  ContractResponse,
  CustomerResponse,
  CustomerMetricsResponse,
  ProgressResponse,
  StatusHistoryResponse,
  SubmitResponse,
  CancelResponse,
  SigningSessionResponse,
  SigningRequestStateResponse,
} from "../types/contractTypes";
import type {
  CustomerRequest,
  ContractRequest,
  AddendumRequest,
} from "../types/contractTypes";

type PageParams = {
  page?: number;
  size?: number;
  sort?: string;
  cursor?: string;
};

export type CustomerListParams = PageParams & { q?: string; status?: string };
export type ContractListParams = PageParams & {
  customerId?: string;
  status?: string;
  serviceGroup?: string;
  q?: string;
  validFromFrom?: string;
  validFromTo?: string;
  validToFrom?: string;
  validToTo?: string;
};
export type AddendumListParams = PageParams & {
  contractId?: string;
  status?: string;
  changeType?: string;
  q?: string;
  effectiveFromFrom?: string;
  effectiveFromTo?: string;
};

export const contractApi = {
  // Customers
  listCustomers: (params: CustomerListParams = {}) =>
    api.get<CustomerResponse[]>(`/customers${toParams(params as Record<string, unknown>)}`).then((r) =>
      toPage<CustomerResponse>(r as unknown as { data: unknown; meta?: PageMeta }),
    ),
  getCustomer: (id: string) => api.get<CustomerResponse>(`/customers/${id}`).then((r) => r.data),
  getCustomerMetrics: (id: string) => api.get<CustomerMetricsResponse>(`/customers/${id}/metrics`).then((r) => r.data),
  lookupCustomers: (ids: string[]) => api.get<CustomerResponse[]>(`/customers/lookup?ids=${ids.join(",")}`).then((r) => r.data),
  getCustomerContacts: (id: string) =>
    api.get<import("../types/contractTypes").CustomerContactResponse[]>(`/customers/${id}/contacts`).then((r) => r.data),
  createCustomer: (data: CustomerRequest) => api.post<CustomerResponse>("/customers", data).then((r) => r.data),
  updateCustomer: (id: string, data: CustomerRequest) => api.put<CustomerResponse>(`/customers/${id}`, data).then((r) => r.data),
  suspendCustomer: (id: string, reason?: string) => api.post(`/customers/${id}/suspend`, { reason }).then((r) => r.data),
  activateCustomer: (id: string) => api.post(`/customers/${id}/activate`).then((r) => r.data),

  // Contracts
  listContracts: (params: ContractListParams = {}) =>
    api.get<ContractResponse[]>(`/contracts${toParams(params as Record<string, unknown>)}`).then((r) =>
      toPage<ContractResponse>(r as unknown as { data: unknown; meta?: PageMeta }),
    ),
  getContract: (id: string) => api.get<ContractResponse>(`/contracts/${id}`).then((r) => r.data),
  lookupContracts: (ids: string[]) => api.get<ContractResponse[]>(`/contracts/lookup?ids=${ids.join(",")}`).then((r) => r.data),
  createContract: (data: ContractRequest) => api.post<ContractResponse>("/contracts", data).then((r) => r.data),
  updateContract: (id: string, data: ContractRequest) => api.put<ContractResponse>(`/contracts/${id}`, data).then((r) => r.data),
  submitContract: (id: string) => api.post<SubmitResponse>(`/contracts/${id}/submit`).then((r) => r.data),
  cancelContract: (id: string, reason?: string) =>
    api.post<CancelResponse>(`/contracts/${id}/cancel`, reason ? { reason } : {}).then((r) => r.data),
  reviseContract: (id: string) => api.post<ContractResponse>(`/contracts/${id}/revise`).then((r) => r.data),
  progressContract: (id: string) => api.get<ProgressResponse>(`/contracts/${id}/progress`).then((r) => r.data),
  historyContract: (id: string) => api.get<StatusHistoryResponse[]>(`/contracts/${id}/history`).then((r) => r.data),
  sendForSigningContract: (id: string) => api.post<SigningRequestStateResponse>(`/contracts/${id}/send-for-signing`).then((r) => r.data),
  signingRequestStateContract: (id: string) => api.get<SigningRequestStateResponse>(`/contracts/${id}/signing-request`).then((r) => r.data),
  signingSessions: (documentType: "CONTRACT" | "ADDENDUM", documentId: string) =>
    api.get<SigningSessionResponse[]>(`/signing-sessions/by-document/${documentType}/${documentId}`).then((r) => r.data),

  // Addenda
  listAddenda: (params: AddendumListParams = {}) =>
    api.get<AddendumResponse[]>(`/addenda${toParams(params as Record<string, unknown>)}`).then((r) =>
      toPage<AddendumResponse>(r as unknown as { data: unknown; meta?: PageMeta }),
    ),
  getAddendum: (id: string) => api.get<AddendumResponse>(`/addenda/${id}`).then((r) => r.data),
  createAddendum: (data: AddendumRequest) => api.post<AddendumResponse>("/addenda", data).then((r) => r.data),
  updateAddendum: (id: string, data: AddendumRequest) => api.put<AddendumResponse>(`/addenda/${id}`, data).then((r) => r.data),
  submitAddendum: (id: string) => api.post<SubmitResponse>(`/addenda/${id}/submit`).then((r) => r.data),
  cancelAddendum: (id: string, reason?: string) =>
    api.post<CancelResponse>(`/addenda/${id}/cancel`, reason ? { reason } : {}).then((r) => r.data),
  reviseAddendum: (id: string) => api.post<AddendumResponse>(`/addenda/${id}/revise`).then((r) => r.data),
  progressAddendum: (id: string) => api.get<ProgressResponse>(`/addenda/${id}/progress`).then((r) => r.data),
  historyAddendum: (id: string) => api.get<StatusHistoryResponse[]>(`/addenda/${id}/history`).then((r) => r.data),
  sendForSigningAddendum: (id: string) => api.post<SigningRequestStateResponse>(`/addenda/${id}/send-for-signing`).then((r) => r.data),
  signingRequestStateAddendum: (id: string) => api.get<SigningRequestStateResponse>(`/addenda/${id}/signing-request`).then((r) => r.data),

  // Attachments (query-param style)
  listAttachments: (ownerType: "CONTRACT" | "ADDENDUM", ownerId: string) =>
    api.get<AttachmentResponse[]>(`/attachments?ownerType=${ownerType}&ownerId=${ownerId}`).then((r) => r.data),
  uploadAttachment: (ownerType: "CONTRACT" | "ADDENDUM", ownerId: string, file: File) => {
    const fd = new FormData();
    fd.append("file", file);
    return api
      .post<AttachmentResponse>(`/attachments?ownerType=${ownerType}&ownerId=${ownerId}`, fd, {
        headers: { "Content-Type": "multipart/form-data" },
      })
      .then((r) => r.data);
  },
  deleteAttachment: (id: string) => api.delete(`/attachments/${id}`).then((r) => r.data),
  downloadAttachmentUrl: (id: string) => `/api/v1/attachments/${id}`,
};
