import { api } from "@/shared/api/client";
import { toPage, toParams, type PageMeta } from "@/shared/api/paging";
import type {
  AddLineRequest,
  AdjustmentRequest,
  CalculateStatementRequest,
  EditLineRequest,
  StatementResponse,
  WorkflowProgressResponse,
} from "../types/billingTypes";

export type StatementListParams = {
  status?: string;
  q?: string;
  page?: number;
  size?: number;
};

export const billingApi = {
  listStatements: (params: StatementListParams = {}) =>
    api
      .get<StatementResponse[]>(`/payment-statements${toParams(params as Record<string, unknown>)}`)
      .then((r) => toPage<StatementResponse>(r as unknown as { data: unknown; meta?: PageMeta })),
  getStatement: (id: string) =>
    api.get<StatementResponse>(`/payment-statements/${id}`).then((r) => r.data),
  workflowProgress: (id: string) =>
    api.get<WorkflowProgressResponse>(`/payment-statements/${id}/workflow-progress`).then((r) => r.data),

  calculate: (request: CalculateStatementRequest) =>
    api.post<StatementResponse>("/payment-statements/calculate", request).then((r) => r.data),
  recalculate: (id: string) =>
    api.post<StatementResponse>(`/payment-statements/${id}/recalculate`).then((r) => r.data),
  reconcile: (id: string) =>
    api.post<StatementResponse>(`/payment-statements/${id}/reconcile`).then((r) => r.data),
  submit: (id: string) =>
    api.post<StatementResponse>(`/payment-statements/${id}/submit`).then((r) => r.data),
  revise: (id: string) =>
    api.post<StatementResponse>(`/payment-statements/${id}/revise`).then((r) => r.data),
  sendForSigning: (id: string) =>
    api.post<StatementResponse>(`/payment-statements/${id}/send-sign`).then((r) => r.data),
  publish: (id: string) =>
    api.post<StatementResponse>(`/payment-statements/${id}/publish`).then((r) => r.data),
  cancel: (id: string, reason?: string) =>
    api.post<StatementResponse>(`/payment-statements/${id}/cancel`, reason ? { reason } : {}).then((r) => r.data),

  addLine: (id: string, request: AddLineRequest) =>
    api.post<StatementResponse>(`/payment-statements/${id}/lines`, request).then((r) => r.data),
  editLine: (id: string, request: EditLineRequest) =>
    api.patch<StatementResponse>(`/payment-statements/${id}/lines`, request).then((r) => r.data),
  createAdjustment: (id: string, request: AdjustmentRequest) =>
    api.post<StatementResponse>(`/payment-statements/${id}/adjustments`, request).then((r) => r.data),
};
