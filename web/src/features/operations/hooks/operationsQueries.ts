import { queryOptions } from "@tanstack/react-query";
import { operationsApi } from "../services/operationsApi";
import type { VolumeFilters } from "../types/operationsTypes";

export const periodsQuery = queryOptions({
  queryKey: ["operation-periods"],
  queryFn: operationsApi.listPeriods,
});

export const volumesQuery = (filters: VolumeFilters = {}) => queryOptions({
  queryKey: ["volume-records", filters],
  queryFn: () => operationsApi.listVolumes(filters),
});
