import { api } from "@/shared/api/client";
import { toParams } from "@/shared/api/paging";
import type { ApprovalAction, ApprovalInboxResponse, ApprovalTab } from "../types/approvalTypes";

export const approvalApi = {
  inbox: (tab: ApprovalTab) =>
    api.get<ApprovalInboxResponse>(`/inbox${toParams({ tab })}`).then((response) => response.data),
  act: (stepInstanceId: string, action: ApprovalAction, comment?: string) =>
    api.post(`/workflow-steps/${stepInstanceId}/actions`, { action, comment: comment || null }).then(() => undefined),
};

