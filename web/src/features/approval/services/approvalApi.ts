import { api } from "@/shared/api/client";
import { toParams } from "@/shared/api/paging";
import type { ApprovalAction, ApprovalInboxResponse, ApprovalTab } from "../types/approvalTypes";

export const approvalApi = {
  inbox: (tab: ApprovalTab, params: { page: number; size: number; q?: string; documentType?: string; priority?: string }) =>
    api.get<ApprovalInboxResponse>(`/inbox${toParams({ tab, ...params })}`).then((response) => response.data),
  act: (stepInstanceId: string, action: ApprovalAction, idempotencyKey: string, comment?: string) =>
    api.post(`/workflow-steps/${stepInstanceId}/actions`, { action, idempotencyKey, comment: comment || null }).then(() => undefined),
};
