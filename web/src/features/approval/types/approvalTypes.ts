export type ApprovalTab = "ASSIGNED" | "SUBMITTED" | "COMPLETED";
export type ApprovalAction = "APPROVE" | "REJECT" | "REQUEST_REVISION";

export type ApprovalInboxItem = {
  instanceId: string;
  stepInstanceId: string | null;
  documentTypeCode: string;
  documentId: string;
  documentNo: string;
  customerName: string | null;
  status: string;
  priority: string;
  currentStepOrder: number;
  currentStepName: string | null;
  currentStepRole: string | null;
  stepActivatedAt: string | null;
  createdAt: string;
  requestedBy: string | null;
  requestedByName: string | null;
};

import type { PagedResult } from "@/shared/api/paging";

export type ApprovalInboxResponse = PagedResult<ApprovalInboxItem>;
