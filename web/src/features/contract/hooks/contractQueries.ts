import { queryOptions } from "@tanstack/react-query";
import { contractApi, type CustomerListParams, type ContractListParams, type AddendumListParams } from "../services/contractApi";

export const customersQuery = (params: CustomerListParams = {}) =>
  queryOptions({ queryKey: ["customers", params], queryFn: () => contractApi.listCustomers(params) });

export const customerQuery = (id: string) =>
  queryOptions({ queryKey: ["customer", id], queryFn: () => contractApi.getCustomer(id), enabled: !!id });

export const customerMetricsQuery = (id: string) =>
  queryOptions({ queryKey: ["customer-metrics", id], queryFn: () => contractApi.getCustomerMetrics(id), enabled: !!id });

export const customerLookupsQuery = (ids: string[]) =>
  queryOptions({ queryKey: ["customer-lookups", ids], queryFn: () => contractApi.lookupCustomers(ids), enabled: ids.length > 0 });

export const contractsQuery = (params: ContractListParams = {}) =>
  queryOptions({ queryKey: ["contracts", params], queryFn: () => contractApi.listContracts(params) });

export const contractQuery = (id: string) =>
  queryOptions({ queryKey: ["contract", id], queryFn: () => contractApi.getContract(id), enabled: !!id });

export const contractLookupsQuery = (ids: string[]) =>
  queryOptions({ queryKey: ["contract-lookups", ids], queryFn: () => contractApi.lookupContracts(ids), enabled: ids.length > 0 });

export const contractProgressQuery = (id: string) =>
  queryOptions({ queryKey: ["contract-progress", id], queryFn: () => contractApi.progressContract(id), enabled: !!id });

export const contractHistoryQuery = (id: string) =>
  queryOptions({ queryKey: ["contract-history", id], queryFn: () => contractApi.historyContract(id), enabled: !!id });

export const addendaQuery = (params: AddendumListParams = {}) =>
  queryOptions({ queryKey: ["addenda", params], queryFn: () => contractApi.listAddenda(params) });

export const addendumQuery = (id: string) =>
  queryOptions({ queryKey: ["addendum", id], queryFn: () => contractApi.getAddendum(id), enabled: !!id });

export const addendumProgressQuery = (id: string) =>
  queryOptions({ queryKey: ["addendum-progress", id], queryFn: () => contractApi.progressAddendum(id), enabled: !!id });

export const addendumHistoryQuery = (id: string) =>
  queryOptions({ queryKey: ["addendum-history", id], queryFn: () => contractApi.historyAddendum(id), enabled: !!id });

export const signingSessionsQuery = (documentType: "CONTRACT" | "ADDENDUM", documentId: string) =>
  queryOptions({
    queryKey: ["signing-sessions", documentType, documentId],
    queryFn: () => contractApi.signingSessions(documentType, documentId),
    enabled: !!documentId,
  });

export const signingRequestStateQuery = (documentType: "CONTRACT" | "ADDENDUM", documentId: string) =>
  queryOptions({
    queryKey: ["signing-request-state", documentType, documentId],
    queryFn: () => documentType === "CONTRACT"
      ? contractApi.signingRequestStateContract(documentId)
      : contractApi.signingRequestStateAddendum(documentId),
    enabled: !!documentId,
  });

export const attachmentsQuery = (ownerType: "CONTRACT" | "ADDENDUM", ownerId: string) =>
  queryOptions({
    queryKey: ["attachments", ownerType, ownerId],
    queryFn: () => contractApi.listAttachments(ownerType, ownerId),
    enabled: !!ownerId,
  });
