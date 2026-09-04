import { api } from "@/shared/api/client";
import { toParams } from "@/shared/api/paging";
import type { CreateVolumeRequest, PeriodResponse, UpdateVolumeRequest, VolumeFilters, VolumePageResponse, VolumeResponse } from "../types/operationsTypes";

export const operationsApi = {
  listPeriods: () => api.get<PeriodResponse[]>("/periods").then((response) => response.data),
  createPeriod: (periodCode: string) =>
    api.post<PeriodResponse>("/periods", { periodCode }).then((response) => response.data),
  lockPeriod: (periodCode: string) =>
    api.post<PeriodResponse>(`/periods/${periodCode}/lock`).then((response) => response.data),
  listVolumes: (filters: VolumeFilters = {}) =>
    api.get<VolumePageResponse>(`/volume-records${toParams(filters)}`).then((response) => response.data),
  createVolume: (request: CreateVolumeRequest) =>
    api.post<VolumeResponse>("/volume-records", request).then((response) => response.data),
  updateVolume: (id: string, request: UpdateVolumeRequest) =>
    api.put<VolumeResponse>(`/volume-records/${id}`, request).then((response) => response.data),
};
