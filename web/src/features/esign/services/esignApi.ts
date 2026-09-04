import { api } from "@/shared/api/client";
import { toPage, toParams, type PageMeta } from "@/shared/api/paging";
import type { SigningSessionResponse } from "../types/esignTypes";

export type SigningSessionListParams = {
  status?: string;
  page?: number;
  size?: number;
  sort?: string;
};

export const esignApi = {
  listSessions: (params: SigningSessionListParams = {}) =>
    api
      .get<SigningSessionResponse[]>(`/signing-sessions${toParams(params as Record<string, unknown>)}`)
      .then((r) => toPage<SigningSessionResponse>(r as unknown as { data: unknown; meta?: PageMeta })),
  getSession: (id: string) =>
    api.get<SigningSessionResponse>(`/signing-sessions/${id}`).then((r) => r.data),
  getByDocument: (documentType: string, documentId: string) =>
    api.get<SigningSessionResponse[]>(`/signing-sessions/by-document/${documentType}/${documentId}`).then((r) => r.data),
  cancel: (id: string, reason?: string) =>
    api.post<SigningSessionResponse>(`/signing-sessions/${id}/cancel`, reason ? { reason } : {}).then((r) => r.data),
};
