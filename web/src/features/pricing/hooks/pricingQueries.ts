import { queryOptions } from "@tanstack/react-query";
import { pricingApi, type PriceListFilters } from "../services/pricingApi";

export const priceListsQuery = (filters: PriceListFilters = {}) => queryOptions({
  queryKey: ["price-lists", filters],
  queryFn: () => pricingApi.listPriceLists(filters),
});

export const priceListVersionsQuery = (priceListId: string) => queryOptions({
  queryKey: ["price-list-versions", priceListId],
  queryFn: () => pricingApi.listVersions(priceListId),
  enabled: Boolean(priceListId),
});

export const priceListVersionQuery = (priceListId: string, versionId: string) => queryOptions({
  queryKey: ["price-list-version", priceListId, versionId],
  queryFn: () => pricingApi.getVersion(priceListId, versionId),
  enabled: Boolean(priceListId && versionId),
});

export const priceListVersionByIdQuery = (versionId: string) => queryOptions({
  queryKey: ["price-list-version-by-id", versionId],
  queryFn: () => pricingApi.getVersionById(versionId),
  enabled: Boolean(versionId),
});

export const serviceItemsQuery = queryOptions({
  queryKey: ["service-items", "active"],
  queryFn: pricingApi.listServiceItems,
  staleTime: 5 * 60_000,
});
