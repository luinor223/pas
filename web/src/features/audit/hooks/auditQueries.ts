import { queryOptions } from "@tanstack/react-query";
import { auditApi, type AuditParams } from "../services/auditApi";

export const auditRecordsQuery = (params: AuditParams = {}) =>
  queryOptions({ queryKey: ["audit-records", params], queryFn: () => auditApi.search(params) });
