import { queryOptions } from "@tanstack/react-query";
import { approvalApi } from "../services/approvalApi";
import type { ApprovalTab } from "../types/approvalTypes";

export const approvalInboxQuery = (tab: ApprovalTab) =>
  queryOptions({
    queryKey: ["approval-inbox", tab],
    queryFn: () => approvalApi.inbox(tab),
  });

