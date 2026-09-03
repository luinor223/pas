import { api } from "@/shared/api/client";
import { toParams } from "@/shared/api/paging";
import type {
  CreatePriceListRequest,
  CreatePriceListVersionRequest,
  PriceLineInput,
  PriceListResponse,
  PriceListVersionDetail,
  PriceListVersionResponse,
  ServiceItemResponse,
} from "../types/pricingTypes";

export type PriceListFilters = {
  customerId?: string;
  contractId?: string;
  serviceGroup?: string;
};

export const pricingApi = {
  listPriceLists: (filters: PriceListFilters = {}) =>
    api.get<PriceListResponse[]>(`/price-lists${toParams(filters)}`).then((response) => response.data),
  createPriceList: (request: CreatePriceListRequest) =>
    api.post<PriceListResponse>("/price-lists", request).then((response) => response.data),
  listVersions: (priceListId: string) =>
    api.get<PriceListVersionResponse[]>(`/price-lists/${priceListId}/versions`).then((response) => response.data),
  createVersion: (priceListId: string, request: CreatePriceListVersionRequest) =>
    api.post<PriceListVersionResponse>(`/price-lists/${priceListId}/versions`, request).then((response) => response.data),
  getVersion: (priceListId: string, versionId: string) =>
    api.get<PriceListVersionDetail>(`/price-lists/${priceListId}/versions/${versionId}`).then((response) => response.data),
  getVersionById: (versionId: string) =>
    api.get<PriceListVersionDetail>(`/price-lists/versions/${versionId}`).then((response) => response.data),
  replaceLines: (priceListId: string, versionId: string, lines: PriceLineInput[]) =>
    api.put<PriceListVersionDetail>(`/price-lists/${priceListId}/versions/${versionId}/lines`, { lines })
      .then((response) => response.data),
  submitVersion: (priceListId: string, versionId: string) =>
    api.post<PriceListVersionResponse>(`/price-lists/${priceListId}/versions/${versionId}/submit`)
      .then((response) => response.data),
  reviseVersion: (priceListId: string, versionId: string) =>
    api.post<PriceListVersionResponse>(`/price-lists/${priceListId}/versions/${versionId}/revise`)
      .then((response) => response.data),
  listServiceItems: () =>
    api.get<ServiceItemResponse[]>("/service-items?activeOnly=true").then((response) => response.data),
};
