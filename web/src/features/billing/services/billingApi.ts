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
  getStatement: (statementNo: string) =>
    api.get<StatementResponse>(`/payment-statements/${statementNo}`).then((r) => r.data),
  workflowProgress: (statementNo: string) =>
    api.get<WorkflowProgressResponse>(`/payment-statements/${statementNo}/workflow-progress`).then((r) => r.data),

  calculate: (request: CalculateStatementRequest) =>
    api.post<StatementResponse>("/payment-statements/calculate", request).then((r) => r.data),
  recalculate: (statementNo: string) =>
    api.post<StatementResponse>(`/payment-statements/${statementNo}/recalculate`).then((r) => r.data),
  reconcile: (statementNo: string) =>
    api.post<StatementResponse>(`/payment-statements/${statementNo}/reconcile`).then((r) => r.data),
  submit: (statementNo: string) =>
    api.post<StatementResponse>(`/payment-statements/${statementNo}/submit`).then((r) => r.data),
  revise: (statementNo: string) =>
    api.post<StatementResponse>(`/payment-statements/${statementNo}/revise`).then((r) => r.data),
  sendForSigning: (statementNo: string) =>
    api.post<StatementResponse>(`/payment-statements/${statementNo}/send-sign`).then((r) => r.data),
  publish: (statementNo: string) =>
    api.post<StatementResponse>(`/payment-statements/${statementNo}/publish`).then((r) => r.data),
  cancel: (statementNo: string, reason?: string) =>
    api.post<StatementResponse>(`/payment-statements/${statementNo}/cancel`, reason ? { reason } : {}).then((r) => r.data),

  addLine: (statementNo: string, request: AddLineRequest) =>
    api.post<StatementResponse>(`/payment-statements/${statementNo}/lines`, request).then((r) => r.data),
  editLine: (statementNo: string, request: EditLineRequest) =>
    api.patch<StatementResponse>(`/payment-statements/${statementNo}/lines`, request).then((r) => r.data),
  createAdjustment: (statementNo: string, request: AdjustmentRequest) =>
    api.post<StatementResponse>(`/payment-statements/${statementNo}/adjustments`, request).then((r) => r.data),
};
