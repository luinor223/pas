import { queryOptions } from "@tanstack/react-query";
import { approvalApi } from "../services/approvalApi";
import type { ApprovalTab } from "../types/approvalTypes";

export const approvalInboxQuery = (
  tab: ApprovalTab,
  params: { page: number; size: number; q?: string; documentType?: string; priority?: string },
) =>
  queryOptions({
    queryKey: ["approval-inbox", tab, params],
    queryFn: () => approvalApi.inbox(tab, params),
  });
