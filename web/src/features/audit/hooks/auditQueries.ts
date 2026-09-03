import { keepPreviousData, queryOptions } from "@tanstack/react-query";
import { auditApi, type AuditParams } from "../services/auditApi";

export const auditRecordsQuery = (params: AuditParams = {}) =>
  queryOptions({
    queryKey: ["audit-records", params],
    queryFn: () => auditApi.search(params),
    // Keeps the page total stable while the next page loads; otherwise the
    // clamp below sees zero pages and sends the user back to page one.
    placeholderData: keepPreviousData,
  });
