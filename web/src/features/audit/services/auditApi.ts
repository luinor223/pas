import { api } from "@/shared/api/client";
import { toPage, toParams, type PageMeta } from "@/shared/api/paging";
import type { AuditRecordResponse } from "../types/auditTypes";

export type AuditParams = {
  entityType?: string;
  query?: string;
  entityNo?: string;
  actorId?: string;
  sourceService?: string;
  action?: string;
  from?: string; // ISO-8601 with offset; a bare date fails to bind
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
};

export const auditApi = {
  search: (params: AuditParams = {}) =>
    api
      .get<AuditRecordResponse[]>(`/audit-records${toParams(params as Record<string, unknown>)}`)
      .then((r) => toPage<AuditRecordResponse>(r as unknown as { data: unknown; meta?: PageMeta })),
};
