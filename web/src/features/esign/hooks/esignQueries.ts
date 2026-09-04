import { queryOptions } from "@tanstack/react-query";
import { esignApi, type SigningSessionListParams } from "../services/esignApi";

export const signingSessionsQuery = (params: SigningSessionListParams = {}) =>
  queryOptions({ queryKey: ["signing-sessions", params], queryFn: () => esignApi.listSessions(params) });

export const signingSessionQuery = (id: string) =>
  queryOptions({
    queryKey: ["signing-session", id],
    queryFn: () => esignApi.getSession(id),
    enabled: !!id,
  });
